package com.vistajet.model.dao;

import static com.vistajet.model.util.EntityHelper.safe;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import com.vistajet.model.dto.AircraftNextEventDto;
import com.vistajet.model.dto.AircraftPreviousEventDto;
import com.vistajet.model.dto.AircraftTypeDto;
import com.vistajet.model.dto.AirportDto;
import com.vistajet.model.dto.CompanyDto;
import com.vistajet.model.dto.EmptyLegConfigAvailabilityDto;
import com.vistajet.model.dto.EmptyLegOfferDto;
import com.vistajet.model.dto.EmptyLegOfferFilterDto;
import com.vistajet.model.dto.EmptyLegRouteDto;
import com.vistajet.model.dto.EmptyLegRoutePushNotificationDto;
import com.vistajet.model.dto.OfferStatusDto;
import com.vistajet.model.dto.TimelineEmptyLegOfferDto;
import com.vistajet.model.dto.VistaJetUserDto;
import com.vistajet.model.dto.availability.AircraftAvailabilityDto;
import com.vistajet.model.dto.identifier.EmptyLegOfferId;
import com.vistajet.model.dto.identifier.IdHelper;
import com.vistajet.model.dto.utils.CompositeKey;
import com.vistajet.model.entity.Aircraft;
import com.vistajet.model.entity.Airport;
import com.vistajet.model.entity.EmptyLegConfigAvailability;
import com.vistajet.model.entity.EmptyLegOffer;
import com.vistajet.model.entity.EmptyLegRoute;
import com.vistajet.model.entity.FlightLeg;
import com.vistajet.model.entity.OfferStatus;
import com.vistajet.model.entity.VistaJetUser;
import com.vistajet.model.exception.VtsException;
import com.vistajet.model.util.EntityHelper;
import com.vistajet.model.utils.ApplicationTimeUtils;
import com.vistajet.model.vo.EmptyLegRouteVo;
import com.vistajet.model.vo.TimelineEmptyLegOfferVo;
import org.apache.commons.collections.CollectionUtils;
import org.joda.time.DateMidnight;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

@Repository("emptyLegOfferManager")
@Transactional
public class EmptyLegOfferManagerImpl implements EmptyLegOfferManager {
    private static final Logger LOGGER = Logger.getLogger(EmptyLegOfferManagerImpl.class.getName());

    @PersistenceContext(unitName = "corePU")
    private EntityManager entityManager;

    private static final DateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private FlightLegManager flightLegManager;

    @Autowired
    private AircraftManager aircraftManager;

    @Autowired
    private AircraftScheduleNoteManager aircraftScheduleNoteManager;

    @Override
    public TimelineEmptyLegOfferDto getTimelineEmptyLeg(Integer id){
        List<TimelineEmptyLegOfferDto> result = getTimelineEmptyLegOffersByIds(Sets.newHashSet(EmptyLegOfferId.getInstance(id)));
        return result.size() == 0 ? null : result.get(0);
    }

    @Override
    public void saveEmptyLegOffer(EmptyLegOffer emptyLegOffer) throws VtsException {
        try {
            entityManager.merge(emptyLegOffer);
        }catch (Exception e){
            throw new VtsException("Error while canceling empty leg offer after changing ferry advertising status" + e.getCause());
        }
    }


    @Override
    public EmptyLegOfferDto saveEmptyLegOffer(EmptyLegOfferDto emptyLegOfferDto) throws VtsException {
        //When cancelling a reserved EmptyLeg created by GV it should come back to previus status
        switch (emptyLegOfferDto.getOfferStatus().getId()){
            case OfferStatusDto.ADVERTISED : emptyLegOfferDto.setWasAdvertised(true);
                break;
            case OfferStatusDto.NEW : emptyLegOfferDto.setWasAdvertised(false);
                break;
        }

        if (EntityHelper.isIdSet(emptyLegOfferDto.getId())) {
            List<EmptyLegOfferDto> emptyLegOfferDtos = getNotCancelledEmptyLegsInsideSegment(emptyLegOfferDto.getAircraft().getId(),
                    emptyLegOfferDto.getSegmentStart() != null ? emptyLegOfferDto.getSegmentStart() : new Date(),
                    emptyLegOfferDto.getSegmentEnd() != null ? emptyLegOfferDto.getSegmentEnd() : emptyLegOfferDto.getEmptyLegRoutes().get(0).getValidTo());

            List<EmptyLegOfferDto> toRemove = new ArrayList<>();
            for(EmptyLegOfferDto eloDto : emptyLegOfferDtos){
                if(eloDto.getId().equals(emptyLegOfferDto.getId())){
                    toRemove.add(eloDto);
                }
            }
            emptyLegOfferDtos.removeAll(toRemove);

            if (emptyLegOfferDtos.size() > 0) {
                emptyLegOfferDto.setHasSegmentAlready(true);
                return emptyLegOfferDto;
            }

            return update(emptyLegOfferDto);
        } else {
            if (emptyLegOfferDto.getOriginalFerry() != null && EntityHelper.isIdSet(emptyLegOfferDto.getOriginalFerry().getId())){
                EmptyLegOfferDto elOfferDto =  getEmptyLegByFerryId(emptyLegOfferDto.getOriginalFerry().getId());
                if (elOfferDto != null){
                    for ( EmptyLegRouteDto emptyLegRouteDto : elOfferDto.getEmptyLegRoutes()){
                        EmptyLegRoute emptyLegRoute = emptyLegRouteDtoToEntity(emptyLegRouteDto);
                        emptyLegRoute.setIsActive(Boolean.FALSE);
                        //deactivate Rotes
                        entityManager.merge(emptyLegRoute);
                    }
                    //reactivate EmptyLeg Expired
                    emptyLegOfferDto.setId(elOfferDto.getId());
                    return update(emptyLegOfferDto);
                }
            }
            return save(emptyLegOfferDto);
        }
    }


    private EmptyLegOfferDto save(EmptyLegOfferDto emptyLegOfferDto) throws VtsException{
        try {
            EmptyLegOffer emptyLegOffer = emptyLegOfferDtoToEntity(emptyLegOfferDto);

            ArrayList emptyLegRoutes = new ArrayList();
            for (EmptyLegRouteDto emptyLegRouteDto : emptyLegOfferDto.getEmptyLegRoutes()){
                EmptyLegRoute emptyLegRoute = emptyLegRouteDtoToEntity(emptyLegRouteDto);
                emptyLegRoute.setEmptyLegOffer(emptyLegOffer);
                emptyLegRoute.setIsActive(Boolean.TRUE);
                emptyLegRoutes.add(emptyLegRoute);
            }
            emptyLegOfferDto.setEmptyLegRoutes(emptyLegRoutes);
            emptyLegOffer.setEmptyLegRoutes(emptyLegRoutes);
            if((emptyLegOffer.getSegmentStart() == null || emptyLegOffer.getSegmentEnd() == null) && emptyLegOffer.getOriginalFerry() != null){
                AircraftPreviousEventDto previousEvent = flightLegManager.getPreviousAircraftEvent(emptyLegOffer.getAircraft().getId(), emptyLegOffer.getOriginalFerry().getScheduledDeparture());
                AircraftNextEventDto nextEvent = flightLegManager.getNextAircraftEvent(emptyLegOffer.getAircraft().getId(), emptyLegOffer.getOriginalFerry().getScheduledArrival());
                Date previous;
                Date next;
                if(previousEvent == null){
                    previous = emptyLegOffer.getOriginalFerry().getScheduledDeparture();
                } else {
                    previous  = previousEvent.getDate();
                }
                if(nextEvent == null){
                    next = emptyLegOffer.getOriginalFerry().getScheduledArrival();
                } else {
                    next = nextEvent.getDate();
                }
                Integer turnaroundTime = getTurnaroundTime();
                emptyLegOffer.setSegmentStart(new Date(previous.getTime() + (turnaroundTime * 60 * 1000)));
                emptyLegOfferDto.setSegmentStart(new Date(previous.getTime() + (turnaroundTime * 60 * 1000)));
                emptyLegOffer.setSegmentEnd(new Date(next.getTime() - (turnaroundTime * 60 * 1000) - (emptyLegOffer.getOriginalFerry().getScheduledArrival().getTime() - emptyLegOffer.getOriginalFerry().getScheduledDeparture().getTime())));
                emptyLegOfferDto.setSegmentEnd(new Date(next.getTime() - (turnaroundTime * 60 * 1000) - (emptyLegOffer.getOriginalFerry().getScheduledArrival().getTime() - emptyLegOffer.getOriginalFerry().getScheduledDeparture().getTime())));
            }

            List<EmptyLegOfferDto> emptyLegOfferDtos = getNotCancelledEmptyLegsInsideSegment(emptyLegOfferDto.getAircraft().getId(),
                    emptyLegOfferDto.getSegmentStart() != null ? emptyLegOfferDto.getSegmentStart() : new Date(),
                    emptyLegOfferDto.getSegmentEnd() != null ? emptyLegOfferDto.getSegmentEnd() : emptyLegOfferDto.getEmptyLegRoutes().get(0).getValidTo());

            List<EmptyLegOfferDto> toRemove = new ArrayList<>();
            for(EmptyLegOfferDto eloDto : emptyLegOfferDtos){
                if(eloDto.getId().equals(emptyLegOfferDto.getId())){
                    toRemove.add(eloDto);
                }
            }
            emptyLegOfferDtos.removeAll(toRemove);

            if (emptyLegOfferDtos.size() > 0) {
                emptyLegOfferDto = emptyLegOffer.asActiveDto();
                emptyLegOfferDto.setHasSegmentAlready(true);
                return emptyLegOfferDto;
            }

            entityManager.persist(emptyLegOffer);
            return emptyLegOffer.asActiveDto();
        } catch (Exception e) {
            throw new VtsException(e);
        }
    }


    public EmptyLegOfferDto update(EmptyLegOfferDto emptyLegOfferDto) throws VtsException{
        try {
            EmptyLegOffer emptyLegOffer = emptyLegOfferDtoToEntity(emptyLegOfferDto);
            ArrayList emptyLegRoutes = new ArrayList();
            for (EmptyLegRouteDto emptyLegRouteDto : emptyLegOfferDto.getEmptyLegRoutes()){

                EmptyLegRoute emptyLegRoute = emptyLegRouteDtoToEntity(emptyLegRouteDto);
                emptyLegRoute.setEmptyLegOffer(emptyLegOffer);
                if(emptyLegRouteDto.getIsActive() != null){
                    emptyLegRoute.setIsActive(emptyLegRouteDto.getIsActive());
                } else {
                    emptyLegRoute.setIsActive(Boolean.TRUE);
                }

                if (EntityHelper.isIdSet(emptyLegRouteDto.getId()))
                    entityManager.merge(emptyLegRoute);
                else
                    entityManager.persist(emptyLegRoute);
                emptyLegRoutes.add(emptyLegRoute);
            }
            entityManager.merge(emptyLegOffer);
            emptyLegOffer.setEmptyLegRoutes(emptyLegRoutes);
            return emptyLegOffer.asActiveDto();
        } catch (Exception e) {
            throw new VtsException(e);
        }
    }



    public EmptyLegOffer emptyLegOfferDtoToEntity(EmptyLegOfferDto emptyLegOfferDto){
        EmptyLegOffer emptyLegOffer;
        if(EntityHelper.idIsSet(emptyLegOfferDto.getId())) {
            emptyLegOffer = entityManager.find(EmptyLegOffer.class, emptyLegOfferDto.getId());
        }
        else{
            emptyLegOffer = new EmptyLegOffer();
        }
        emptyLegOffer.setAircraft(entityManager.find(Aircraft.class, emptyLegOfferDto.getAircraft().getId()));
        if(emptyLegOfferDto.getOriginalFerry() != null) {
            emptyLegOffer.setOriginalFerry(entityManager.find(FlightLeg.class, emptyLegOfferDto.getOriginalFerry().getId()));
        } else {
            emptyLegOffer.setOriginalFerry(null);
        }
        emptyLegOffer.setOfferStatus(entityManager.find(OfferStatus.class, emptyLegOfferDto.getOfferStatus().getId()));

        emptyLegOffer.setSegmentStart(emptyLegOfferDto.getSegmentStart());
        emptyLegOffer.setSegmentEnd(emptyLegOfferDto.getSegmentEnd());
        if(emptyLegOfferDto.getSegArrivalAirport() != null) {
            emptyLegOffer.setSegArrivalAirport(entityManager.find(Airport.class, emptyLegOfferDto.getSegArrivalAirport().getId()));
        } else {
            emptyLegOffer.setSegArrivalAirport(entityManager.find(Airport.class, emptyLegOfferDto.getOriginalFerry().getArrivalAirport().getId()));
        }
        if(emptyLegOfferDto.getSegDepartureAirport() != null) {
            emptyLegOffer.setSegDepartureAirport(entityManager.find(Airport.class, emptyLegOfferDto.getSegDepartureAirport().getId()));
        } else {
            emptyLegOffer.setSegDepartureAirport(entityManager.find(Airport.class, emptyLegOfferDto.getOriginalFerry().getDepartureAirport().getId()));
        }
        emptyLegOffer.setNotes(emptyLegOfferDto.getNotes());
        if(emptyLegOfferDto.getWasAdvertised() != null) {
            emptyLegOffer.setWasAdvertised(emptyLegOfferDto.getWasAdvertised());
        }

        if (emptyLegOfferDto.getWasAdvertisedToAvinode() != null) {
            emptyLegOffer.setWasAdvertisedToAvinode(emptyLegOfferDto.getWasAdvertisedToAvinode());
        }

        emptyLegOffer.setCancellationReason(emptyLegOfferDto.getCancellationReason());
        return emptyLegOffer;
    }


    public EmptyLegRoute emptyLegRouteDtoToEntity(EmptyLegRouteDto emptyLegRouteDto){
        EmptyLegRoute emptyLegRoute;
        if (EntityHelper.isIdSet(emptyLegRouteDto.getId()))
            emptyLegRoute = entityManager.find(EmptyLegRoute.class,emptyLegRouteDto.getId());
        else
            emptyLegRoute = new EmptyLegRoute();
        emptyLegRoute.setAmountCents(emptyLegRouteDto.getAmountCents());
        emptyLegRoute.setArrivalAirport(entityManager.find(Airport.class, emptyLegRouteDto.getArrivalAirport().getId()));
        if(emptyLegRoute.getCreatedTime() == null){
            DateTime now = new DateTime(DateTimeZone.UTC);
            emptyLegRoute.setCreatedTime(now.toDate());
        } else {
            emptyLegRoute.setCreatedTime(emptyLegRouteDto.getCreatedTime());
        }
        emptyLegRoute.setDepartureAirport(entityManager.find(Airport.class, emptyLegRouteDto.getDepartureAirport().getId()));
        emptyLegRoute.setCreator(entityManager.find(VistaJetUser.class, emptyLegRouteDto.getCreator().getId()));
        emptyLegRoute.setMinDeparture(emptyLegRouteDto.getMinDepartureTime());
        emptyLegRoute.setMaxDeparture(emptyLegRouteDto.getMaxDepartureTime());
        emptyLegRoute.setAvinodeValidFrom(emptyLegRouteDto.getAvinodeValidFrom());
        emptyLegRoute.setAvinodeValidTo(emptyLegRouteDto.getAvinodeValidTo());
        emptyLegRoute.setFspValidFrom(emptyLegRouteDto.getFspValidFrom());
        emptyLegRoute.setFspValidTo(emptyLegRouteDto.getFspValidTo());
        emptyLegRoute.setValidFrom(emptyLegRouteDto.getValidFrom());
        emptyLegRoute.setValidTo(emptyLegRouteDto.getValidTo());
        return emptyLegRoute;
    }

    @Override
    public OfferStatusDto getOfferStatus(Integer id){
        OfferStatusDto offerStatusDto = entityManager.find(OfferStatus.class, id).asDto();
        return offerStatusDto != null ? offerStatusDto : null;
    }

    @Override
    public void setOfferStatus(Integer emptyLegId, Integer newStatusId, String newStatusName) {
        EmptyLegOffer el = entityManager.find(EmptyLegOffer.class, emptyLegId);
        el.setOfferStatus(new OfferStatus(newStatusId, newStatusName));
    }

    @Override
    public EmptyLegOffer getEmptyLegById(Integer emptyLegId) {
        return entityManager.find(EmptyLegOffer.class, emptyLegId);
    }

    @Override
    public EmptyLegOfferDto getEmptyLegByIdAsDto(Integer emptyLegId) {
        EmptyLegOffer emptyLegOffer = getEmptyLegById(emptyLegId);
        if(emptyLegOffer != null) {
            return emptyLegOffer.asDto();
        } else {
            return null;
        }
    }

    @Override
    public EmptyLegOfferDto getEmptyLegAndRoutesByFerryId(Integer flightId) {
        List<EmptyLegRoute> emptyLegRoute = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegsByFerryId", EmptyLegRoute.class).setParameter("flightLegId", flightId).setParameter("isActive", true).getResultList();

        if(emptyLegRoute.isEmpty()){
            return new EmptyLegOfferDto();
        } else {
            EmptyLegOffer emptyLegOffer = emptyLegRoute.get(0).getEmptyLegOffer();
            emptyLegOffer.setEmptyLegRoutes(emptyLegRoute);
            return emptyLegOffer.asDto();
        }
    }

    @Override
    public EmptyLegOfferDto getEmptyLegByFerryId(Integer flightId) {
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegOffers", EmptyLegOffer.class)
                .setParameter("flightLegId", flightId)
                .getResultList();
        if (offerResultList.size() > 0)
            return offerResultList.get(0).asDto();
        return null;
    }

    @Override
    public EmptyLegOffer retrieveEmptyLegByFerryId(Integer flightId) {
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegOffers", EmptyLegOffer.class)
                .setParameter("flightLegId", flightId)
                .getResultList();
        if (offerResultList.size() > 0)
            return offerResultList.get(0);
        return null;
    }

    @Override
    public EmptyLegOffer getEmptyLegEntityByFerryId(Integer flightId) {
        final List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegOffersEntity", EmptyLegOffer.class)
                .setParameter("flightLegId", flightId)
                .getResultList();
        if (offerResultList != null && !offerResultList.isEmpty())
            return offerResultList.get(0);
        return null;
    }

    @Override
    public List<EmptyLegConfigAvailabilityDto> getEmptyLegConfigAvailability() {
        List<EmptyLegConfigAvailability> result = entityManager.createNamedQuery("EmptyLegConfigAvailability.getEmptyLegAvailability", EmptyLegConfigAvailability.class)
                .getResultList();

        List<EmptyLegConfigAvailabilityDto> dtoList =  new ArrayList<>();
        for(EmptyLegConfigAvailability entry:result)
            dtoList.add(entry.asDto());

        return dtoList;
    }

    @Override
    public EmptyLegConfigAvailabilityDto getEmptyLegConfigAvailabilityByOperator(Integer operatorId) {
        if(!operatorId.equals(CompanyDto.JET_AVIATION_ID)){
            operatorId = CompanyDto.VJ_ID;
        }
        final List<EmptyLegConfigAvailabilityDto> result = entityManager.createNamedQuery("EmptyLegConfigAvailability.getEmptyLegAvailabilityByOperator",
                EmptyLegConfigAvailabilityDto.class).setParameter("operatorId", operatorId).getResultList();

        if (result != null && !result.isEmpty()) {
            return result.get(0);
        }
        return null;
    }

    @Override
    public Integer getTurnaroundTime(){
        List<EmptyLegConfigAvailability> result = entityManager.createNamedQuery("EmptyLegConfigAvailability.getEmptyLegAvailability", EmptyLegConfigAvailability.class)
                .getResultList();
        return result.get(0).getTurnaroundTime();
    }


    @Override
    public EmptyLegOfferDto getEmptyLegBySegment(Integer aircraftId, Date segmentStart, Date segmentEnd) {
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegBySegment", EmptyLegOffer.class)
                .setParameter("aicraftId", aircraftId)
                .setParameter("segmentStart", segmentStart)
                .setParameter("segmentEnd", segmentEnd)
                .getResultList();
        if (offerResultList.size() > 0) {
            return offerResultList.get(0).asDto();
        }

        return null;
    }

    @Override
    public EmptyLegOfferDto getEmptyLegByMinMaxDeparture(Integer aircraftId, Date minDeparture, Date maxDeparture) {
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegByMinMaxDeparture", EmptyLegOffer.class)
                .setParameter("aicraftId", aircraftId)
                .setParameter("minDeparture", minDeparture)
                .setParameter("maxDeparture", maxDeparture)
                .getResultList();
        if (offerResultList.size() > 0) {
            return offerResultList.get(0).asDto();
        }

        return null;
    }

    @Override
    public List<EmptyLegOfferDto> getNotCancelledEmptyLegsInsideSegment(Integer aircraftId, Date startDate, Date endDate) {
        List<EmptyLegOfferDto> emptyLegs = new ArrayList<>();
        List<Integer> excludedStatus = new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED,OfferStatusDto.SOLD));
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getNotCancelledEmptyLegsInsideSegment", EmptyLegOffer.class)
                .setParameter("aicraftId", aircraftId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setParameter("excludedOfferIds",excludedStatus)
                        .getResultList();
        if (offerResultList.size() > 0) {
            for(EmptyLegOffer el : offerResultList){
                emptyLegs.add(el.asDto());
            }
        }
        return emptyLegs;
    }

    @Override
    public List<EmptyLegOfferDto> getEmptyLegsInsideSegment(Integer aircraftId, Date startDate, Date endDate) {
        List<EmptyLegOfferDto> emptyLegs = new ArrayList<>();
        List<Integer> excludedOfferIds = new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED));
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegsInsideSegment", EmptyLegOffer.class)
                .setParameter("aicraftId", aircraftId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setParameter("excludedOfferIds",excludedOfferIds)
                .getResultList();
        if (offerResultList.size() > 0) {
            for(EmptyLegOffer el : offerResultList){
                emptyLegs.add(el.asDto());
            }
        }
        return emptyLegs;
    }

    @Override
    public List<EmptyLegOfferDto> getOverllapingEmptyLegs(Integer aircraftId, Date startDate, Date endDate) {
        List<EmptyLegOfferDto> emptyLegs = new ArrayList<>();
        List<Integer> excludedOfferIds = new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED));
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getOverllapingEmptyLegs", EmptyLegOffer.class)
                .setParameter("aircraftId", aircraftId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setParameter("excludedOfferIds",excludedOfferIds)
                .getResultList();
        if (offerResultList.size() > 0) {
            for(EmptyLegOffer el : offerResultList){
                emptyLegs.add(el.asDto());
            }
        }
        return emptyLegs;
    }

    @Override
    public List<EmptyLegOffer> getOverllpingEmptyLegsOnAircraftEvents(AircraftAvailabilityDto dto) {
        List <EmptyLegOffer> emptyLegOffers = new ArrayList<>();

        Integer aircraftId = null;

        AircraftTypeDto aircraftTypeDto =  entityManager.createNamedQuery("AircraftType.getAircraftTypeIdPerName", AircraftTypeDto.class).
                setParameter("name",dto.getAircraftType()).getSingleResult();

        if(dto.getTailNumber() != null){
            aircraftId = entityManager.createNamedQuery("Aircraft.getAircraftIdByTailNumber",Integer.class).
                    setParameter("tailNumber",dto.getTailNumber()).getSingleResult();
        }

        List<Integer> offerIds =
                new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED, OfferStatusDto.RESERVED,OfferStatusDto.SOLD));

        if(dto.getTailNumber() == null){
            if(dto.getEndDate() == null){
                emptyLegOffers = entityManager.createNamedQuery("EmptyLegOffer.getOffersPerAircraftType",EmptyLegOffer.class).
                        setParameter("startDate",dto.getStartDate()).
                        setParameter("aircraftTypeId",aircraftTypeDto.getId()).
                        setParameter("excludedOfferIds",offerIds).getResultList();
            }else{
                emptyLegOffers = entityManager.createNamedQuery("EmptyLegOffer.getOffersPerAircraftTypeAndTime",EmptyLegOffer.class).
                        setParameter("startDate",dto.getStartDate()).
                        setParameter("endDate",dto.getEndDate()).
                        setParameter("aircraftTypeId",aircraftTypeDto.getId()).
                        setParameter("excludedOfferIds",offerIds).getResultList();
            }
        }else{
            if(dto.getEndDate() == null){
                emptyLegOffers = entityManager.createNamedQuery("EmptyLegOffer.getOffersPerAircraftTail",EmptyLegOffer.class).
                        setParameter("startDate",dto.getStartDate()).
                        setParameter("aircraftId",aircraftId).
                        setParameter("excludedOfferIds",offerIds).getResultList();
            }else{
                emptyLegOffers = entityManager.createNamedQuery("EmptyLegOffer.getOverllapingEmptyLegs",EmptyLegOffer.class).
                        setParameter("startDate",dto.getStartDate()).
                        setParameter("endDate",dto.getEndDate()).
                        setParameter("aircraftId",aircraftId).
                        setParameter("excludedOfferIds",offerIds).getResultList();
            }
        }
        return emptyLegOffers;
    }

    @Override
    public List<EmptyLegOfferDto> getCancelledEmptyLegsInsideSegment(Integer aircraftId, Date startDate, Date endDate) {
        List<EmptyLegOfferDto> emptyLegs = new ArrayList<>();
        List<Integer> statusIds = new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED));
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegsInsideSegmentByStatus", EmptyLegOffer.class)
                .setParameter("aicraftId", aircraftId)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setParameter("statusId", statusIds)
                .getResultList();
        if (offerResultList.size() > 0) {
            for(EmptyLegOffer el : offerResultList){
                emptyLegs.add(el.asDto());
            }
        }
        return emptyLegs;
    }

    @Override
    public Boolean segmentHasEmptyLeg(Integer aircraftId, Date segmentStart) {
        List<Integer> excludedOfferIds = new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED,OfferStatusDto.SOLD));
        List<EmptyLegOffer> offerResultList = entityManager.createNamedQuery("EmptyLegOffer.getEmptyLegBySegmentStart", EmptyLegOffer.class)
                .setParameter("aicraftId", aircraftId)
                .setParameter("segmentStart", segmentStart)
                .setParameter("excludedOfferIds",excludedOfferIds)
                .getResultList();
        if (offerResultList.size() > 0) {
            return true;
        }
        return false;
    }

    @Override
    public List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersByIds(Set<EmptyLegOfferId> emptyLegIds) {
        Iterable<List<EmptyLegOfferId>> partitions = Iterables.partition(emptyLegIds, 200);
        List<EmptyLegOffer> result = Lists.newArrayList();
        for (List<EmptyLegOfferId> partition : partitions) {
            result.addAll(entityManager.createNamedQuery("EmptyLegOffer.getTimelineEmptyLegOffersByIds", EmptyLegOffer.class)
                    .setParameter("ids", IdHelper.convert(partition))
                    .getResultList());
        }

        return convertToTimeline(result);
    }

    @Override
    public List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersDtoByIds(Collection<EmptyLegOfferId> emptyLegIds) {
        List<TimelineEmptyLegOfferVo> result = new ArrayList<>();
        for (List<EmptyLegOfferId> partition : Iterables.partition(emptyLegIds, 1000)) {
            result.addAll(entityManager.createNamedQuery("EmptyLegOffer.getTimelineEmptyLegOffersDtoByIds", TimelineEmptyLegOfferVo.class)
                    .setParameter("ids", IdHelper.convert(partition))
                    .getResultList());
        }
        return convertToTimelineEmptyLegOfferDto(result);
    }

    @Override
    public List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersDtoByStatus(Collection<Integer> emptyLegIds) {
        List<TimelineEmptyLegOfferVo> result = new ArrayList<>();
        for (List<Integer> partition : Iterables.partition(emptyLegIds, 1000)) {
            result.addAll(entityManager.createNamedQuery("EmptyLegOffer.getTimelineEmptyLegOffersDtoByStatus", TimelineEmptyLegOfferVo.class)
                    .setParameter("ids", partition)
                    .getResultList());
        }
        return convertToTimelineEmptyLegOfferDto(result);
    }

    private List<EmptyLegRouteVo> getActiveEmptyLegRoutes() {
        return entityManager.createNamedQuery("EmptyLegOffer.getActiveEmptyLegRoutes", EmptyLegRouteVo.class)
                    .getResultList();
    }

    private Map<Integer, EmptyLegRouteVo> listToEmptyLegIdEmptyLegRouteVoMap(List<EmptyLegRouteVo> list) {
        Map<Integer, EmptyLegRouteVo> res = new HashMap<>();
        for (EmptyLegRouteVo dto : safe(list)) {
            if (!res.containsKey(dto.getEmptyLegId())){// skip if this ID already in MAP (in map only routes with max ID's for this leg)
                res.put(dto.getEmptyLegId(), dto);
            }
        }
        return res;
    }

    private List<TimelineEmptyLegOfferDto> convertToTimelineEmptyLegOfferDto(Collection<TimelineEmptyLegOfferVo> vos) {
        ArrayList<TimelineEmptyLegOfferDto> res = new ArrayList<>();

        Map<Integer, EmptyLegRouteVo> emptyLegRoutesMap = listToEmptyLegIdEmptyLegRouteVoMap(getActiveEmptyLegRoutes());

        for (TimelineEmptyLegOfferVo vo : vos) {
            EmptyLegRouteVo routeVo = emptyLegRoutesMap.get(vo.getId());
            TimelineEmptyLegOfferDto dto = new TimelineEmptyLegOfferDto.Builder()
                    .id(vo.getId())
                    .aircraftId(vo.getAircraftId())
                    .segmentStart(vo.getSegmentStart())
                    .segmentEnd(vo.getSegmentEnd())
                    .airportId(routeVo.getDepartureAirportId())
                    .validFrom(routeVo.getValidFrom())
                    .validTo(routeVo.getValidTo())
                    .fspValidFrom(routeVo.getFspValidFrom())
                    .fspValidTo(routeVo.getFspValidTo())
                    .avinodeValidFrom(routeVo.getAvinodeValidFrom())
                    .avinodeValidTo(routeVo.getAvinodeValidTo())
                    .creatorName(routeVo.getCreatorFirstName() + " " + routeVo.getCreatorLastName())
                    .emptyLegRoutes(Collections.singletonList(convertEmptyLegRouteVoToDto(routeVo))) // only first
                    .offerStatus(new OfferStatusDto(vo.getOfferStatusId(), vo.getOfferStatusName(), vo.getOfferStatusDescription()))
                    .cancellationReason(vo.getCancellationReason())
                    .build();
            res.add(dto);
        }
        return res;
    }

    private EmptyLegRouteDto convertEmptyLegRouteVoToDto(EmptyLegRouteVo vo) {
        return EmptyLegRouteDto.newBuilder()
                .id(vo.getId())
                .validFrom(vo.getValidFrom())
                .validTo(vo.getValidTo())
                .fspValidFrom(vo.getFspValidFrom())
                .fspValidTo(vo.getFspValidTo())
                .avinodeValidFrom(vo.getAvinodeValidFrom())
                .avinodeValidTo(vo.getAvinodeValidTo())
                .creator(new VistaJetUserDto(vo.getCreatorId()))
                .departureAirport(new AirportDto(vo.getDepartureAirportId(), vo.getDepartureAirportIcao(), vo.getDepartureAirportName()))
                .arrivalAirport(new AirportDto(vo.getArrivalAirportId(), vo.getArrivalAirportIcao(), vo.getArrivalAirportName()))
                .amountCents(vo.getAmountCents())
                .minDepartureTime(vo.getMinDepartureTime())
                .maxDepartureTime(vo.getMaxDepartureTime())
                .build();
    }

    private List<TimelineEmptyLegOfferDto> convertToTimeline(List<EmptyLegOffer> entities) {
        List<TimelineEmptyLegOfferDto> res = new ArrayList<>();
        for (EmptyLegOffer entity : entities) {
            res.add(new TimelineEmptyLegOfferDto(entity.asDtoForTimeline()));
        }
        return res;
    }

    @Override
    public List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersByDate(Date startTime, Date endTime) {
        List<Integer> excludedOfferIds = new ArrayList<>(Arrays.asList(OfferStatusDto.SOLD));
        List<EmptyLegOffer> result = entityManager.createNamedQuery("EmptyLegOffer.getTimelineEmptyLegOffersByDate", EmptyLegOffer.class)
                .setParameter("startTime", startTime)
                .setParameter("endTime", endTime)
                .setParameter("excludedOfferIds", excludedOfferIds)
                .getResultList();
        return convertToTimeline(result);
    }

    @Override
    public List<EmptyLegOffer> getEmptyLegOffersValidToBeCancelOverlapPeriod(Date startTime, Date endTime, Integer aircraftId) {
        final List<Integer> excludeOfferStatusId = new ArrayList<>(Arrays.asList(OfferStatusDto.CANCELLED, OfferStatusDto.SOLD, OfferStatusDto.RESERVED));

        final List<EmptyLegOffer> emptyLegOffers = entityManager.createNamedQuery("EmptyLegOffer.getAllOverlapEmptyLegsValidToBeCancel", EmptyLegOffer.class)
                .setParameter("startDate", startTime)
                .setParameter("endDate", endTime)
                .setParameter("excludeOfferStatusId", excludeOfferStatusId)
                .setParameter("aircraftId", aircraftId)
                .getResultList();

        return emptyLegOffers;
    }

    @Override
    public void cancelEmptyLegs(List<EmptyLegOffer> emptyLegOffers, String cancellationReason) {
        for (final EmptyLegOffer emptyLegOffer : emptyLegOffers) {
            emptyLegOffer.setCancellationReason(cancellationReason);
            emptyLegOffer.setOfferStatus(new OfferStatus(OfferStatusDto.CANCELLED));
            entityManager.merge(emptyLegOffer);

            LOGGER.info("Empty Leg offer " + emptyLegOffer.getId() + " was cancelled.");
        }
    }

    @Override
    public List<EmptyLegOfferDto> retrieveValidEmptyLegOffers(ArrayList<Integer> statusList ) {
        EmptyLegOfferFilterDto emptyLegOfferFilterDto = new EmptyLegOfferFilterDto();
        emptyLegOfferFilterDto.setOfferStatus(statusList);
        emptyLegOfferFilterDto.setMaxDeparture(true);
        return retrieveValidEmptyLegOffers(emptyLegOfferFilterDto,true);
    }

    @Override
    public  List<Integer> retrieveFlighLegIdFromEmptyLegOffers(EmptyLegOfferFilterDto emptyLegOfferFilterDto) {

        Date time = new Date();
        if (emptyLegOfferFilterDto.getValidTo() !=null) {
            time = emptyLegOfferFilterDto.getValidTo();
            List<Integer> result = entityManager.createNamedQuery("EmptyLegOffer.getFlightLegIdsByEmptyLegOffersWithValidTo", Integer.class)
                    .setParameter("validTo", time)
                    .setParameter("isActive", Boolean.TRUE)
                    .getResultList();

            return result;
        }
        else{
            List<Integer> result = entityManager.createNamedQuery("EmptyLegOffer.getFlightLegIdsByEmptyLegOffers", Integer.class)
                    .setParameter("time", time)
                    .setParameter("isActive", Boolean.TRUE)
                    .getResultList();

            return result;
        }
    }

    @Override
    public List<EmptyLegOfferDto> retrieveValidEmptyLegOffers(EmptyLegOfferFilterDto emptyLegOfferFilterDto,Boolean filterByStatus) {
            List<EmptyLegOfferDto> dtoResultList = new ArrayList<>();

        StringBuilder resultQueryString = new StringBuilder(" SELECT elo ");
        StringBuilder queryStringBody = new StringBuilder(" FROM EmptyLegOffer elo WHERE id in (")
                .append(" SELECT DISTINCT eo.id FROM EmptyLegOffer eo LEFT JOIN eo.emptyLegRoutes elr ")
                .append(" WHERE elr.isActive = :isActive ");

           if(EntityHelper.isIdSet(emptyLegOfferFilterDto.getId())) {
                queryStringBody.append("AND eo.id = :id ");
           }
           else {
                if (emptyLegOfferFilterDto.getOfferStatus() != null && filterByStatus) {
                    if (emptyLegOfferFilterDto.getOfferStatus().size() > 0) {
                        queryStringBody.append(" AND eo.offerStatus.id in (:offerStatus) ");
                    }
                } else if (!emptyLegOfferFilterDto.getCancelled() || !emptyLegOfferFilterDto.getReserved()) {
                    queryStringBody.append(" AND eo.offerStatus.id not in (:withoutOfferStatus) ");
                }

                if (emptyLegOfferFilterDto.getAircrafts() != null) {
                    queryStringBody.append(" AND eo.aircraft.id in (:aircrafts) ");
                }else {
                    if (emptyLegOfferFilterDto.getAircraftType() != null) {
                       queryStringBody.append(" AND eo.aircraft.aircraftType.id in (:aircraftType) ");
                    }
                }

                if (emptyLegOfferFilterDto.getArrivalAirport() != null){
                   queryStringBody.append(" AND elr.arrivalAirport.id = :arrivalAirport ");
                }

                if (emptyLegOfferFilterDto.getDepartureAirport() != null){
                   queryStringBody.append(" AND elr.departureAirport.id = :departureAirport ");
                }

                if (emptyLegOfferFilterDto.getValidTo() != null && emptyLegOfferFilterDto.getValidFrom() != null) {
                    queryStringBody.append(" AND ((COALESCE(elr.validTo,elr.fspValidTo,elr.avinodeValidTo) >= :validFrom AND COALESCE(elr.validTo,elr.fspValidTo,elr.avinodeValidTo) <= :validTo) ");
                    queryStringBody.append(" OR (COALESCE(elr.validFrom,elr.fspValidFrom,elr.avinodeValidFrom) >= :validFrom AND COALESCE(elr.validFrom,elr.fspValidFrom,elr.avinodeValidFrom) <= :validTo) ");
                    queryStringBody.append(" OR (COALESCE(elr.validFrom,elr.fspValidFrom,elr.avinodeValidFrom) <= :validFrom AND COALESCE(elr.validTo,elr.fspValidTo,elr.avinodeValidTo) >= :validTo)) ");
                }
                else {
                    if (emptyLegOfferFilterDto.getValidTo() == null && emptyLegOfferFilterDto.getValidFrom() == null) {
                        queryStringBody.append(" AND COALESCE(elr.validTo,elr.fspValidTo,elr.avinodeValidTo) >= :time ");
                    }
                    else {
                        if (emptyLegOfferFilterDto.getValidFrom() != null) {
                            queryStringBody.append(" AND (:validFrom BETWEEN COALESCE(TRUNC(elr.validFrom),TRUNC(elr.fspValidFrom),TRUNC(elr.avinodeValidFrom)) AND COALESCE(elr.validTo,elr.fspValidTo,elr.avinodeValidTo)) ");
                        }
                        if (emptyLegOfferFilterDto.getValidTo() != null) {
                            queryStringBody.append(" AND (:validTo BETWEEN COALESCE(TRUNC(elr.validFrom),TRUNC(elr.fspValidFrom),TRUNC(elr.avinodeValidFrom)) AND COALESCE(elr.validTo,elr.fspValidTo,elr.avinodeValidTo)) ");
                        }
                    }
                }

                if (emptyLegOfferFilterDto.getCity() != null){
                    queryStringBody.append(" AND (elr.departureAirport.place.city LIKE :city OR elr.arrivalAirport.place.city LIKE :city)");
                }
                else
                if (emptyLegOfferFilterDto.getCountry() != null){
                   queryStringBody.append(" AND (elr.departureAirport.iso LIKE :iso OR elr.arrivalAirport.iso LIKE :iso)");
                }
                else
                if (emptyLegOfferFilterDto.getRegionsId() != null){
                    queryStringBody.append(" AND (elr.departureAirport.iso IN (select cc.threeLetterCode from CountryCode cc where cc.regions.id IN (:regionsId)) OR elr.arrivalAirport.iso IN (select cc.threeLetterCode from CountryCode cc where cc.regions.id IN (:regionsId) )) ");
                }

                if(emptyLegOfferFilterDto.getMaxDeparture()!=null && emptyLegOfferFilterDto.getMaxDeparture()==true){
                   queryStringBody.append(" AND elr.maxDeparture >= :time ");
                }

               if (CollectionUtils.isNotEmpty(emptyLegOfferFilterDto.getAdvertisedOn())) {
                   List<String> advertisedOn = emptyLegOfferFilterDto.getAdvertisedOn();

                   boolean needAll = advertisedOn.contains("All Targets");
                   if (needAll || advertisedOn.contains("VJ Direct")) {
                       queryStringBody.append(" AND (elr.validFrom IS NOT NULL OR elr.validTo IS NOT NULL)");
                   }
                   if (needAll || advertisedOn.contains("FSP")) {
                       queryStringBody.append(" AND (elr.fspValidFrom IS NOT NULL OR elr.fspValidTo IS NOT NULL)");
                   }
                   if (needAll || advertisedOn.contains("Avinode")) {
                       queryStringBody.append(" AND (elr.avinodeValidFrom IS NOT NULL OR elr.avinodeValidTo IS NOT NULL)");
                   }
               }
           }

        resultQueryString.append(queryStringBody.toString()).append(" ) ORDER BY elo.offerStatus.id ASC ");
        Query resultQuery = entityManager.createQuery(resultQueryString.toString());

        if(EntityHelper.isIdSet(emptyLegOfferFilterDto.getId())){
            resultQuery.setParameter("id",emptyLegOfferFilterDto.getId());
        }
        else
        {
            if (emptyLegOfferFilterDto.getOfferStatus() != null && filterByStatus ) {
                if (emptyLegOfferFilterDto.getOfferStatus().size() > 0) {
                    resultQuery.setParameter("offerStatus", emptyLegOfferFilterDto.getOfferStatus());
                }
            } else if (!emptyLegOfferFilterDto.getCancelled() || !emptyLegOfferFilterDto.getReserved()) {
                ArrayList status = new ArrayList();
                if (!emptyLegOfferFilterDto.getCancelled())
                    status.add(OfferStatusDto.CANCELLED);
                if (!emptyLegOfferFilterDto.getReserved())
                    status.add(OfferStatusDto.RESERVED);
                resultQuery.setParameter("withoutOfferStatus", status);
            }

            if (emptyLegOfferFilterDto.getAircrafts() != null) {
                resultQuery.setParameter("aircrafts", emptyLegOfferFilterDto.getAircrafts());
            }
            else {
                if (emptyLegOfferFilterDto.getAircraftType() != null) {
                    resultQuery.setParameter("aircraftType", emptyLegOfferFilterDto.getAircraftType());
                }
            }

            if (emptyLegOfferFilterDto.getArrivalAirport() != null){
                resultQuery.setParameter("arrivalAirport", emptyLegOfferFilterDto.getArrivalAirport().getId());
            }

            if (emptyLegOfferFilterDto.getDepartureAirport() != null){
                resultQuery.setParameter("departureAirport", emptyLegOfferFilterDto.getDepartureAirport().getId());
            }

            if (emptyLegOfferFilterDto.getValidFrom() != null && emptyLegOfferFilterDto.getValidTo() != null ) {
                Date validTo = new DateMidnight(emptyLegOfferFilterDto.getValidTo()).plusDays(1).toDateTime().minusSeconds(1).toDate();
                resultQuery.setParameter("validTo", validTo);
                Date validFrom = new DateMidnight(emptyLegOfferFilterDto.getValidFrom()).toDate();
                resultQuery.setParameter("validFrom", validFrom);

            } else if (emptyLegOfferFilterDto.getValidFrom() != null) {
                Date validFrom = new DateMidnight(emptyLegOfferFilterDto.getValidFrom()).toDate();
                resultQuery.setParameter("validFrom", validFrom);

            } else if (emptyLegOfferFilterDto.getValidTo() != null) {
                Date validTo = new DateMidnight(emptyLegOfferFilterDto.getValidTo()).toDate();
                resultQuery.setParameter("validTo", validTo);
            }

            if ((emptyLegOfferFilterDto.getValidTo() == null) && (emptyLegOfferFilterDto.getValidFrom() == null)||(emptyLegOfferFilterDto.getMaxDeparture()!=null && emptyLegOfferFilterDto.getMaxDeparture()==true)) {
                resultQuery.setParameter("time", new DateTime(DateTimeZone.UTC).toDate());
            }

            if (emptyLegOfferFilterDto.getCity() != null){
                resultQuery.setParameter("city", "%" + emptyLegOfferFilterDto.getCity().toUpperCase() + "%");
            }
            else
            if (emptyLegOfferFilterDto.getCountry() != null){
                resultQuery.setParameter("iso","%" + emptyLegOfferFilterDto.getCountry() + "%" );
            }
            else
            if (emptyLegOfferFilterDto.getRegionsId() != null){
                resultQuery.setParameter("regionsId",  emptyLegOfferFilterDto.getRegionsId());
            }
        }
        resultQuery.setParameter("isActive", Boolean.TRUE);

        List<EmptyLegOffer> queryResult = resultQuery.getResultList();

        for (EmptyLegOffer entry : queryResult) {

            EmptyLegOfferDto el = entry.asActiveDto();

            StringBuilder operatingCompanyQueryString = new StringBuilder("SELECT new com.vistajet.model.dto.CompanyDto(c.id,c.name)");
            StringBuilder operatingCompanyQueryBody = new StringBuilder("FROM Aircraft a JOIN a.operatingCompany c");

            if(el.getAircraft() != null &&  el.getAircraft().getId() != null)
            {
                operatingCompanyQueryBody.append(" WHERE a.id = (:id)");
                operatingCompanyQueryString.append(operatingCompanyQueryBody.toString());

            }

            Query operatingCompanyResultQuery = entityManager.createQuery(operatingCompanyQueryString.toString());

            if(el.getAircraft() != null &&  el.getAircraft().getId() != null)
            {
                operatingCompanyResultQuery.setParameter("id", el.getAircraft().getId());
            }

            CompanyDto companyResultDto = (CompanyDto) operatingCompanyResultQuery.getSingleResult();

            el.setOperatingCompanyId(companyResultDto.getId());
            el.setOperatingCompanyName(companyResultDto.getName());

            el.setIsOperatedByVJ(isOperatedByVistaJet(companyResultDto.getId()));
            el.setIsOperatedByUS(isOperatedByVistaJetUS(companyResultDto.getId()));
            el.setIsOperatedByChina(isOperatedByVistaJetChina(companyResultDto.getId()));

            dtoResultList.add(el);
        }

        return dtoResultList;
    }

    @Override
    public void inactiveEmptyLegRoutes(List<EmptyLegRouteDto> emptyLegRoutesRemovedDtos) throws VtsException {
        for(EmptyLegRouteDto elr : emptyLegRoutesRemovedDtos){
            if (EntityHelper.isIdSet(elr.getId())) {
                EmptyLegRoute emptyLegRoute = emptyLegRouteDtoToEntity(elr);
                emptyLegRoute.setIsActive(Boolean.FALSE);
                entityManager.merge(emptyLegRoute);
            }
        }
    }

    @Override
    public void inactiveEmptyLegRoutesByFind(List<EmptyLegRouteDto> emptyLegRoutesRemovedDtos) throws VtsException {
        for (EmptyLegRouteDto dto : emptyLegRoutesRemovedDtos) {
            if (EntityHelper.isIdSet(dto.getId())) {
                EmptyLegRoute emptyLegRoute = entityManager.find(EmptyLegRoute.class, dto.getId());
                emptyLegRoute.setIsActive(Boolean.FALSE);
            }
        }
    }

    @Override
    public void saveAircraftConfigAvailability(List<EmptyLegConfigAvailabilityDto> dtos) throws VtsException {
        EmptyLegConfigAvailability entity;
        for (final EmptyLegConfigAvailabilityDto dto : dtos) {
            if (dto.getId() != null) {
                entity = entityManager.find(EmptyLegConfigAvailability.class, dto.getId());
                if (dto.getTurnaroundTime() != null) entity.setTurnaroundTime(dto.getTurnaroundTime());
                if (dto.getMinimumGapSize()!= null) entity.setMinimumGapSize(dto.getMinimumGapSize());
                if (dto.getMaximumGapSize()!= null) entity.setMaximumGapSize(dto.getMaximumGapSize());
                if (dto.getOperatorId() != null) entity.setOperatorId(dto.getOperatorId());
                if (dto.getMinimumSectorTime() != null) entity.setMinimumSectorTime(dto.getMinimumSectorTime());
                if (dto.getMinimumTimeWindow() != null) entity.setMinimumTimeWindow(dto.getMinimumTimeWindow());
                if (dto.getMaximumTimeWindow() != null) entity.setMaximumTimeWindow(dto.getMaximumTimeWindow());
                if (dto.getMinimumAdvertisingMargin() != null) entity.setMinimumAdvertisingMargin(dto.getMinimumAdvertisingMargin());
                if (dto.getMaximumAdvertisingMargin() != null) entity.setMaximumAdvertisingMargin(dto.getMaximumAdvertisingMargin());
                if (dto.getAutoAdvIsRunning() != null) entity.setAutoAdvIsRunning(dto.getAutoAdvIsRunning());
            } else {
                entity = new EmptyLegConfigAvailability();
                if (dto.getTurnaroundTime() != null) entity.setTurnaroundTime(dto.getTurnaroundTime());
                if (dto.getMinimumGapSize()!= null) entity.setMinimumGapSize(dto.getMinimumGapSize());
                if (dto.getMaximumGapSize()!= null) entity.setMaximumGapSize(dto.getMaximumGapSize());
                if (dto.getOperatorId() != null) entity.setOperatorId(dto.getOperatorId());
                if (dto.getMinimumSectorTime() != null) entity.setMinimumSectorTime(dto.getMinimumSectorTime());
                if (dto.getMinimumTimeWindow() != null) entity.setMinimumTimeWindow(dto.getMinimumTimeWindow());
                if (dto.getMaximumTimeWindow() != null) entity.setMaximumTimeWindow(dto.getMaximumTimeWindow());
                if (dto.getMinimumAdvertisingMargin() != null) entity.setMinimumAdvertisingMargin(dto.getMinimumAdvertisingMargin());
                if (dto.getMaximumAdvertisingMargin() != null) entity.setMaximumAdvertisingMargin(dto.getMaximumAdvertisingMargin());
                if (dto.getAutoAdvIsRunning() != null) entity.setAutoAdvIsRunning(dto.getAutoAdvIsRunning());
                entityManager.persist(entity);
            }
        }

    }

    @Override
    public Boolean CheckIfEmptyLegIsActivated(Integer emptyLegId) throws VtsException{
        try {
            EmptyLegOffer el = entityManager.find(EmptyLegOffer.class, emptyLegId);
            return(el.getOfferStatus().getId() != OfferStatusDto.SOLD && el.getOfferStatus().getId() != OfferStatusDto.RESERVED);
        }catch(Exception e){
            throw new VtsException(e);
        }
    }

    @Override
    public void checkAndCancelEmptyLeg(EmptyLegOfferDto emptyLegOffer, Boolean needToCheckMinMaxDeparture, Integer newAircraftID, AircraftPreviousEventDto aircraftPreviousEventDto, AircraftNextEventDto aircraftNextEventDto, Integer flightTime, String cancellationReason, Boolean checkPreviousEvent, Boolean checkNextEvent) throws VtsException {
        if(emptyLegOffer.getOfferStatus().getId() == OfferStatusDto.CANCELLED || emptyLegOffer.getOfferStatus().getId() == OfferStatusDto.ADVERTISED || emptyLegOffer.getOfferStatus().getId() == OfferStatusDto.NEW) {
            emptyLegOffer.setCancellationReason(cancellationReason);
            if (needToCheckMinMaxDeparture) {
                if (!checkIfMinAndMaxDepartureStillValid(emptyLegOffer, aircraftPreviousEventDto, aircraftNextEventDto, getTurnaroundTime(), flightTime)) {
                    emptyLegOffer.setOfferStatus(new OfferStatusDto(OfferStatusDto.CANCELLED));
                    update(emptyLegOffer);
                }
            } else {
                if(newAircraftID != null && newAircraftID > 0){
                    emptyLegOffer.setAircraft(aircraftManager.getAircraftById(newAircraftID));
                }
                if(checkPreviousEvent){
                   // case the previousEvent is being verified, its needed to retrieve it again to ensure that all of his values are correct
                   AircraftPreviousEventDto previousEvent = flightLegManager.getPreviousAircraftEvent(newAircraftID, emptyLegOffer.getEmptyLegRoutes().get(0).getMinDepartureTime());
                   if(previousEvent != null && previousEvent.getAirport() != null) {
                       if(!previousEvent.getAirport().getId().equals(emptyLegOffer.getEmptyLegRoutes().get(0).getDepartureAirport().getId())) {
                           emptyLegOffer.setOfferStatus(new OfferStatusDto(OfferStatusDto.CANCELLED));
                           update(emptyLegOffer);
                       }
                   } else { // case there is no previous event, the el should be canceled
                       emptyLegOffer.setOfferStatus(new OfferStatusDto(OfferStatusDto.CANCELLED));
                       update(emptyLegOffer);
                   }
                } else if(checkNextEvent) {
                    AircraftNextEventDto nextEvent = flightLegManager.getNextAircraftEvent(newAircraftID, emptyLegOffer.getEmptyLegRoutes().get(0).getMaxDepartureTime());
                    if (nextEvent != null && nextEvent.getAirportId() != null) {
                        if (!nextEvent.getAirportId().equals(emptyLegOffer.getEmptyLegRoutes().get(0).getArrivalAirport().getId())) {
                            emptyLegOffer.setOfferStatus(new OfferStatusDto(OfferStatusDto.CANCELLED));
                            update(emptyLegOffer);
                        }
                    } else { // case there is no next event, the el should be canceled
                        emptyLegOffer.setOfferStatus(new OfferStatusDto(OfferStatusDto.CANCELLED));
                        update(emptyLegOffer);
                    }
                }
            }
        }
    }

    public Boolean checkIfMinAndMaxDepartureStillValid(EmptyLegOfferDto el, AircraftPreviousEventDto aircraftPreviousEventDto, AircraftNextEventDto aircraftNextEventDto, Integer turnaroundTime, Integer flightTime) throws VtsException {
        for(EmptyLegRouteDto elr : el.getEmptyLegRoutes()) {
            LocalDateTime minDeparture = ApplicationTimeUtils.parseDateTime(sdf.format(elr.getMinDepartureTime()));
            LocalDateTime maxDeparture = ApplicationTimeUtils.parseDateTime(sdf.format(elr.getMaxDepartureTime()));

            // Case previous event overlap or breaks min departure validation
            if (aircraftPreviousEventDto != null && aircraftPreviousEventDto.getDate().after(minDeparture.minusMinutes(turnaroundTime).toDate())) {
                return false;
            }

            if (elr.getMaxDepartureTime() != null && (elr.getMaxDepartureTime().before(elr.getScheduledDepartureUtc()))) {
                return false;
            }

            if (elr.getMinDepartureTime() != null && (elr.getMinDepartureTime().before(el.getSegmentStart()))) {
                return false;
            }

            // Case next event overlap or breaks max departure validation
            if (aircraftNextEventDto != null && aircraftNextEventDto.getDate().before(maxDeparture.plusMinutes(turnaroundTime).plusMinutes(flightTime).toDate())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public EmptyLegRouteDto loadEmptyLegRouteToQuote(Integer emptyLegRouteId) throws VtsException {
        EmptyLegRoute elr = entityManager.createNamedQuery("EmptyLegRoute.loadEmptyLegRouteById", EmptyLegRoute.class)
                .setParameter("elrId", emptyLegRouteId)
                .getSingleResult();

        return elr.asDtoToQuote();
    }


    @Override
    public List<EmptyLegRoutePushNotificationDto> retrieveValidEmptyLegRoutesForPN(Date validFromDate, Date validToDate) {
        List<EmptyLegRoutePushNotificationDto> emptyLegRoutePushNotificationDtos = entityManager.createNamedQuery("EmptyLegRoute.getValidEmptyLegForPN", EmptyLegRoutePushNotificationDto.class)
                .setParameter("offerStatusId", OfferStatusDto.ADVERTISED)
                .setParameter("validFromDate", validFromDate)
                .setParameter("validToDate", validToDate)
                .getResultList();

        Map<CompositeKey<Integer, Integer>, EmptyLegRoutePushNotificationDto> emptyLegMap = new HashMap<>();
        for(EmptyLegRoutePushNotificationDto emptyLegRoutePushNotificationDto : emptyLegRoutePushNotificationDtos){
            CompositeKey<Integer, Integer> compositeKey = new CompositeKey<>(emptyLegRoutePushNotificationDto.getDepartureAirportId(),emptyLegRoutePushNotificationDto.getArrivalAirportId());
            if(emptyLegMap.containsKey(compositeKey)){
                EmptyLegRoutePushNotificationDto dto = emptyLegMap.get(compositeKey);
                dto.getRouteIds().add(emptyLegRoutePushNotificationDto.getEmptyLegOfferId());
            }
            else {
                emptyLegMap.put(compositeKey, emptyLegRoutePushNotificationDto);
            }
        }
        return new ArrayList<EmptyLegRoutePushNotificationDto>(emptyLegMap.values());
    }

    /**
     * Checks if the crew will be available
     * im the desired period and aircraft.
     * @param aircraftId
     * @param fromDate
     * @param toDate
     * @return true if the crew will be available, false otherwise
     */
    public boolean hasCrewAvailableInPeriod(Integer aircraftId, Date fromDate, Date toDate){
        return aircraftScheduleNoteManager.getTotalAircraftNoteForCrewNotAvailableInPeriod(aircraftId, fromDate, toDate) == 0l;
    }

    @Override
    public void saveEmptyLeg(EmptyLegOffer emptyLegOffer) throws Exception{
        emptyLegOffer.setOfferStatus(entityManager.find(OfferStatus.class, OfferStatusDto.ADVERTISED));
        for(EmptyLegRoute emptyLegRoute : emptyLegOffer.getEmptyLegRoutes()) {
            emptyLegRoute.setMinDeparture(roundTimeFifteen(emptyLegRoute.getMinDeparture(), false));
            emptyLegRoute.setMaxDeparture(roundTimeFifteen(emptyLegRoute.getMaxDeparture(), true));
            emptyLegRoute.setValidFrom(roundTimeFifteen(emptyLegRoute.getValidFrom(), false));
            emptyLegRoute.setValidTo(roundTimeFifteen(emptyLegRoute.getValidTo(), true));
            emptyLegRoute.setAvinodeValidFrom(emptyLegRoute.getAvinodeValidFrom() != null ? roundTimeFifteen(emptyLegRoute.getAvinodeValidFrom(), false) : null);
            emptyLegRoute.setAvinodeValidTo(emptyLegRoute.getAvinodeValidTo() != null ? roundTimeFifteen(emptyLegRoute.getAvinodeValidTo(), true) : null);
            emptyLegRoute.setFspValidFrom(roundTimeFifteen(emptyLegRoute.getFspValidFrom(), false));
            emptyLegRoute.setFspValidTo(roundTimeFifteen(emptyLegRoute.getFspValidTo(), true));
        }
        emptyLegOffer.setSegmentStart(roundTimeFifteen(emptyLegOffer.getSegmentStart(), false));
        emptyLegOffer.setSegmentEnd(roundTimeFifteen(emptyLegOffer.getSegmentEnd(), true));

        try {
            if (emptyLegOffer.getId() != null) {
                entityManager.merge(emptyLegOffer);
            } else {
                entityManager.persist(emptyLegOffer);
            }

        } catch (final Exception e) {
            throw e;
        }
    }

    @Override
    public void changeAdvertisedToAvinodeStatus(Integer emptyLegOfferId, Boolean wasAdvertisedToAvinode) {
        EmptyLegOffer emptyLegOffer = getEmptyLegById(emptyLegOfferId);
        if (emptyLegOffer != null) {
            emptyLegOffer.setWasAdvertisedToAvinode(wasAdvertisedToAvinode);

            if (!wasAdvertisedToAvinode) {
                for (EmptyLegRoute emptyLegRoute : emptyLegOffer.getEmptyLegRoutes()) {
                    emptyLegRoute.setAvinodeValidFrom(null);
                    emptyLegRoute.setAvinodeValidTo(null);
                }
            }

            entityManager.merge(emptyLegOffer);
        }
    }

    @Override
    public List<EmptyLegOfferDto> retrieveAvinodeExpiredEmptyLegs() {
        List<EmptyLegOffer> emptyLegOffers = entityManager.createNamedQuery("EmptyLegOffer.getAvinodeExpiredEmptyLegs", EmptyLegOffer.class)
            .setParameter("offerStatusId", OfferStatusDto.ADVERTISED)
            .setParameter("currentTime", new DateTime(DateTimeZone.UTC).toDate())
            .getResultList();

        List<EmptyLegOfferDto> result = Lists.newArrayList();

        for (EmptyLegOffer emptyLegOffer : emptyLegOffers) {
            result.add(emptyLegOffer.asDto());
        }

        return result;
    }

    public boolean isOperatedByVistaJet(Integer companyId)
    {
        return companyId.equals(CompanyDto.VJ_ID);
    }


    public boolean isOperatedByVistaJetUS(Integer companyId)
    {
        return companyId.equals(CompanyDto.VJ_US_ID.get(0)) || companyId.equals(CompanyDto.VJ_US_ID.get(1));
    }

    public boolean isOperatedByVistaJetChina(Integer companyId)
    {
        return companyId.equals(CompanyDto.VJ_CHINA_ID);
    }

    private Date roundTimeFifteen(Date ref, Boolean type) {
        //type: FALSE(round to greater); TRUE(round to lower);
        Date dt = new Date(900000 * (ref.getTime() / 900000));
        //round if rounded time is greater then next
        if(type && dt.after(ref))
            dt.setTime(dt.getTime() - 900000);
        //round if rounded time is lower then previous
        if(!type && dt.before(ref))
            dt.setTime(dt.getTime() + 900000);

        return dt;
    }
}
