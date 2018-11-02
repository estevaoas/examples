package com.vistajet.business.logic;

import com.vistajet.business.core.service.emptyleg.EmptyLegOfferService;
import com.vistajet.business.logic.flightleg.change.event.*;
import com.vistajet.business.logic.observer.ObserverParameters;
import com.vistajet.business.logic.observer.flightleg.FlightLegUpdateEvent;
import com.vistajet.business.logic.observer.flightleg.FlightLegUpdateSubject;
import com.vistajet.business.logic.observer.flightleg.crew.CrewUpdateEvent;
import com.vistajet.business.logic.observer.flightorder.BaseFlightOrderUpdateObserver;
import com.vistajet.business.logic.observer.flightorder.FlightOrderAtomicUpdateEvent;
import com.vistajet.business.logic.observer.flightorder.FlightOrderUpdateEvent;
import com.vistajet.business.logic.observer.flightorder.FlightOrderUpdateSubject;
import com.vistajet.model.dao.*;
import com.vistajet.model.dao.availability.AircraftAvailabilityManager;
import com.vistajet.model.dao.availability.TimelineEventManager;
import com.vistajet.model.dto.*;
import com.vistajet.model.dto.availability.AircraftAvailabilityDto;
import com.vistajet.model.dto.identifier.OrderBusinessStatusId;
import com.vistajet.model.entity.*;
import com.vistajet.model.entity.availability.AircraftAvailability;
import com.vistajet.model.entity.availability.TimelineEvent;
import com.vistajet.model.entity.bpm.PfaFlightLegApproval;
import com.vistajet.model.exception.VtsException;
import com.vistajet.model.listeners.*;
import com.vistajet.model.listeners.changeobjects.AircraftAvailabilityChangeObject;
import com.vistajet.model.listeners.changeobjects.NoteChangeObject;
import com.vistajet.model.listeners.changeobjects.TimelineEventsAvailabilityChangeObject;
import com.vistajet.model.listeners.list.UpdatedDtoConstants;
import com.vistajet.model.util.cache.CacheHelper;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
@Transactional(rollbackFor = Exception.class)
public class AvailabilityObserverImpl extends BaseFlightOrderUpdateObserver implements AvailabilityObserver, NotesObserver {
    private static final Logger LOGGER = Logger.getLogger(AvailabilityObserverImpl.class);

    private static final String MSG_CANCELLATION_FLIGHT_PREFIX = "Flight with id #";
    private static final String MSG_CANCELLATION_MAINTENANCE_PREFIX = "Maintenance event with id #";

    private static final String MSG_CANCELLATION_OVERLAP = " overlapped the offer";
    private static final String MSG_BREAK_MIN_DEPARTURE = " too close to (or has overlapped) offer's minimum departure time";
    private static final String MSG_BREAK_MAX_DEPARTURE = " too close to (or has overlapped) offer's maximum departure time";

    private static final String MSG_CANCELLATION_FLIGHT_DEPARTURE_AIRPORT_CHANGE = " has changed departure airport to ";
    private static final String MSG_CANCELLATION_FLIGHT_ARRIVAL_AIRPORT_CHANGE = " has changed arrival airport to ";
    private static final String MSG_CANCELLATION_MAINTENANCE_AIRPORT_CHANGE = " has changed airport to ";

    private static final String MSG_CANCELLATION_EVENT_MOVED = " has been moved";
    private static final String MSG_CANCELLATION_EVENT_CREATED = " has been created";
    private static final String MSG_CANCELLATION_FLIGHT_CANCELLED = " has been cancelled";
    private static final String MSG_CANCELLATION_MAINTENANCE_DELETED = " has been deleted";

    private static final String MSG_CANCELLATION_AIRCRAFT_CHANGE = MSG_CANCELLATION_EVENT_MOVED+" to aircraft ";

    private static final String MSG_PREVIOUS_EVENT_NOT_MATCHING = " and offer's departure airport is not matching previous event anymore";
    private static final String MSG_NEXT_EVENT_NOT_MATCHING = " and offer's arrival airport is not matching next event anymore";

    private static final String MSG_ORIGINAL_FERRY_CHANGED = "Original ferry with id #";
    private static final String MSG_DEPARTURE_AIRPORT_CHANGED = " and offer's departure airport is not matching anymore";
    private static final String MSG_ARRIVAL_AIRPORT_CHANGED = " and offer's arrival airport is not matching anymore";
    private static final String MSG_CREW = "crew not available";
    private static final DateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    private static final String MSG_AIRCRAFT_UNAVAILABLE = "has a restriction period";


    @Autowired
    private FlightLegUpdateSubject flightLegUpdateSubject;

    @Autowired
    private EmptyLegOfferService emptyLegOfferService;

    @Autowired
    private OneWayOfferManager oneWayOfferManager;

    @Autowired
    private FlightLegManager flightLegManager;

    @Autowired
    private MaintenanceManager maintenanceManager;

    @Autowired
    private CacheHelper cacheHelper;

    @Autowired
    private FlightOrderUpdateSubject flightOrderUpdateSubject;

    @Autowired
    private AircraftScheduleNoteListenerForTimeline aircraftScheduleNoteListenerForTimeline;

    @Autowired
    EmptyLegOfferManager emptyLegOfferManager;

    @Autowired
    AircraftAvailabilityManager aircraftAvailabilityManager;

    @Autowired
    AircraftScheduleNoteManager aircraftScheduleNoteManager;

    @Autowired
    TimelineEventManager timelineEventManager;

    @Autowired
    AircraftAvailabilityListener aircraftAvailabilityListener;

    @Autowired
    TimelineEventsAvailabilityListener timelineEventsAvailabilityListener;

    @Autowired
    AircraftManager aircraftManager;

    @Autowired
    AircraftTypeManager aircraftTypeManager;

    private Boolean changedAircraft = false;
    private Integer PREVIOUS_EVENT = 1;
    private Integer NEXT_EVENT = 2;
    private Boolean maintenanceCancelled = false;
    private Boolean maintenanceMoved = false;
    private Boolean flightMoved = false;
    private Boolean checkPreviousEvent = false;
    private Boolean checkNextEvent = false;



    @PostConstruct
    public void init() {
        flightOrderUpdateSubject.addObserver(this);
        flightLegUpdateSubject.addObserver(this);
        aircraftScheduleNoteListenerForTimeline.addObserver(this);
        aircraftAvailabilityListener.addObserver(this);
        timelineEventsAvailabilityListener.addObserver(this);
    }

    @Override
    public void onActivateOnDemandFlightLeg(final RouteLeg routeLeg) {
        try {
            PfaFlightLegApproval pfaFlightLegApproval = getPfaFlightLegApproval(routeLeg);
            handleFlightLegUpdate(pfaFlightLegApproval);
        } catch (VtsException e) {
            LOGGER.error(String.format("One Way cannot be activated %s", routeLeg.getFlightLeg().getId()),e);
        }
    }

    @Override
    public void onCancelFlight(final RouteLeg routeLeg) {
        try {
            PfaFlightLegApproval pfaFlightLegApproval = getPfaFlightLegApproval(routeLeg);
            if(routeLeg.getFlightLeg() != null){
                if (routeLeg.getFlightLeg().getAircraft() != null){
                    pfaFlightLegApproval.setOldAircraft(routeLeg.getFlightLeg().getAircraft());
                }
                pfaFlightLegApproval.setOldArrivalAirport(routeLeg.getFlightLeg().getArrivalAirport());
            }

            pfaFlightLegApproval.setCancelled(true);
            handleFlightLegUpdate(pfaFlightLegApproval);
        } catch (Exception e) {
            LOGGER.error(String.format("One Way cannot be canceled %s", routeLeg.getFlightLeg().getId()),e);
        }
    }

    private PfaFlightLegApproval getPfaFlightLegApproval(RouteLeg routeLeg) {
        return new PfaFlightLegApproval.Builder()
                .withAdded(true)
                .withCancelled(false)
                .withFlightLeg(routeLeg.getFlightLeg())
                .withNewAircraft(routeLeg.getFlightLeg().getAircraft())
                .withNewArrivalAirport(routeLeg.getFlightLeg().getArrivalAirport())
                .withNewDepartureAirport(routeLeg.getFlightLeg().getDepartureAirport())
                .withNewScheduledArrival(routeLeg.getFlightLeg().getScheduledArrival())
                .withNewScheduledDeparture(routeLeg.getFlightLeg().getScheduledDeparture())
                .build();
    }

    @Override
    public void handleFlightLegUpdate(FlightLegUpdateEvent updateEvent) throws VtsException {
    }

    @Override
    public void handleFlightLegUpdateAlter(FlightLegUpdateEvent updateEvent) throws VtsException{
    }

    @Override
    public void handleFlightLegUpdate(PfaFlightLegApproval flightLegApproval) throws VtsException {
        RouteLeg routeLeg = null;
        if (flightLegApproval.getFlightLeg().getRouteLegs().size() > 0)
            routeLeg = flightLegApproval.getFlightLeg().getRouteLegs().get(0);
        if ((flightLegApproval.getAdded() ||
                flightLegApproval.getCancelled() ||
                changedAircraft(flightLegApproval) ||
                changedEstimated(flightLegApproval) ||
                changedScheduled(flightLegApproval) ||
                changedAirport(flightLegApproval)
        )&&
                ((routeLeg != null) && !routeLeg.getLegBusinessType().getId().equals(LegBusinessTypeDto.OPERATIONAL_FERRY))) {
            handleEmptyLeg(flightLegApproval);
            handleOneWay(flightLegApproval);

        } else if((changedAircraft(flightLegApproval) || changedAirport(flightLegApproval)) && routeLeg.getLegBusinessType().getId().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
            EmptyLegOfferDto el = emptyLegOfferManager.getEmptyLegByFerryId(flightLegApproval.getFlightLeg().getId());
            if(el != null ) {
                checkAndCancelEmptyLegWhenOriginalFerryIsChanged(flightLegApproval,el);
            }
            handleOneWay(flightLegApproval);
        }
    }

    // this method checks all EL cancelation scenarios regarding original ferry
    public void checkAndCancelEmptyLegWhenOriginalFerryIsChanged(PfaFlightLegApproval flightLegApproval, EmptyLegOfferDto emptyLegOfferDto)throws VtsException{
        String cancellationReason = MSG_ORIGINAL_FERRY_CHANGED + flightLegApproval.getFlightLeg().getId();
        if(emptyLegOfferDto.getOfferStatus().getId().equals(OfferStatusDto.ADVERTISED) || emptyLegOfferDto.getOfferStatus().getId().equals(OfferStatusDto.NEW)) {
            // the instructions below check original ferry cancelation scenarios only when the elo is advertised or new
            //  this instruction checks the original ferry aircraft change
            if (changedAircraft(flightLegApproval)) {
                cancellationReason += MSG_CANCELLATION_EVENT_MOVED + " to aircraft " + flightLegApproval.getNewAircraft().getTailNumber();
                cancelEmptyLeg(emptyLegOfferDto, cancellationReason);
                return;
            }

            // this instruction checks the original ferry airport change(both arrival and departure)
            if (changedAirport(flightLegApproval)) {
                EmptyLegRouteDto elr = emptyLegOfferDto.getEmptyLegRoutes().get(0);
                // if the variable below is true, it indicates that a change occured in the original ferry departure airport
                Boolean isDepartureAirportChanged = flightLegApproval.getNewDepartureAirport() != null && flightLegApproval.getOldDepartureAirport() != null
                        && !(flightLegApproval.getNewDepartureAirport().getId().equals(flightLegApproval.getOldDepartureAirport().getId()));

                // this instruction checks if the departure airport changes and if both elr and original ferry departure airport matches
                if (isDepartureAirportChanged && !elr.getDepartureAirport().getId().equals(flightLegApproval.getNewDepartureAirport().getId())) {
                    cancellationReason += MSG_CANCELLATION_FLIGHT_DEPARTURE_AIRPORT_CHANGE + flightLegApproval.getNewDepartureAirport().getIcao() + MSG_DEPARTURE_AIRPORT_CHANGED;
                    cancelEmptyLeg(emptyLegOfferDto, cancellationReason);
                    return;
                }
                // if the variable below is true, it indicates that a change occured in the original ferry arrival airport
                Boolean isArrivalAirportChanged = flightLegApproval.getNewArrivalAirport() != null && flightLegApproval.getOldArrivalAirport() != null
                        && !(flightLegApproval.getNewArrivalAirport().getId().equals(flightLegApproval.getOldArrivalAirport()));

                // this instruction checks if the departure airport changes and if both elr and original ferry departure airport matches
                if (isArrivalAirportChanged && !elr.getArrivalAirport().getId().equals(flightLegApproval.getNewArrivalAirport().getId())) {
                    cancellationReason += MSG_CANCELLATION_FLIGHT_DEPARTURE_AIRPORT_CHANGE + flightLegApproval.getNewArrivalAirport().getIcao() + MSG_ARRIVAL_AIRPORT_CHANGED;
                    cancelEmptyLeg(emptyLegOfferDto, cancellationReason);
                    return;
                }

            }
        }
    }

    private boolean changedAircraft(PfaFlightLegApproval flightLegApproval){
        return (flightLegApproval.getOldAircraft() != null && flightLegApproval.getNewAircraft() != null
                && flightLegApproval.getOldAircraft() != flightLegApproval.getNewAircraft());
    }

    private boolean changedEstimated(PfaFlightLegApproval flightLegApproval){
        return (flightLegApproval.getOldEstimatedArrival() != null && flightLegApproval.getNewEstimatedArrival() != null && flightLegApproval.getOldEstimatedArrival() != flightLegApproval.getNewEstimatedArrival()) ||
                (flightLegApproval.getOldEstimatedDeparture() != null && flightLegApproval.getNewEstimatedDeparture() != null && flightLegApproval.getOldEstimatedDeparture() != flightLegApproval.getNewEstimatedDeparture()) ;
    }

    private boolean changedScheduled(PfaFlightLegApproval flightLegApproval){
        return (flightLegApproval.getOldScheduledArrival() != null && flightLegApproval.getNewScheduledArrival() != null && flightLegApproval.getOldScheduledArrival() != flightLegApproval.getNewScheduledArrival()) ||
                (flightLegApproval.getOldScheduledDeparture() != null && flightLegApproval.getNewScheduledDeparture() != null && flightLegApproval.getOldScheduledDeparture() != flightLegApproval.getNewScheduledDeparture());
    }

    private boolean changedAirport(PfaFlightLegApproval flightLegApproval){
        return (flightLegApproval.getOldDepartureAirport() != null && flightLegApproval.getNewDepartureAirport() != null && !flightLegApproval.getOldDepartureAirport().equals(flightLegApproval.getNewDepartureAirport())) ||
                (flightLegApproval.getOldArrivalAirport() != null && flightLegApproval.getNewArrivalAirport() != null && !flightLegApproval.getOldArrivalAirport().equals(flightLegApproval.getNewArrivalAirport())) ;
    }

    @Override
    public void checkAndCancelEmptyLegWhenMaintenanceChanges(Integer maintenanceID, TimelineMaintenanceDto oldMaintenance, Integer changeType) throws VtsException {
        handleOneWay(maintenanceID, oldMaintenance, changeType);
        handleEmptyLeg(maintenanceID, oldMaintenance, changeType);
    }

    private void handleOneWay(PfaFlightLegApproval flightLegApproval) {
        FlightLeg updatedFlightLeg = flightLegApproval.getFlightLeg();
        Date departure = updatedFlightLeg.getScheduledDeparture();
        Date arrival = updatedFlightLeg.getScheduledArrival();
        flightMoved = false;

        if (updatedFlightLeg.getEstimatedDeparture() != null && departure.after(updatedFlightLeg.getEstimatedDeparture())){
            departure = updatedFlightLeg.getEstimatedDeparture();
        }
        if(updatedFlightLeg.getEstimatedArrival() != null && arrival.before(updatedFlightLeg.getEstimatedArrival())){
            arrival = updatedFlightLeg.getEstimatedArrival();
        }
        try {
            AircraftPreviousEventDto aircraftPreviousEventDto;
            AircraftNextEventDto aircraftNextEventDto;

            List<OneWayOfferDto> ows;
            String cancellationReason;

            //Cancel One Ways in case flight overlaps the one way segment
            ows = oneWayOfferManager.getOneWaysThatShouldBeCancelled(flightLegApproval.getNewAircraft().getId(),
                    flightLegApproval.getNewEstimatedDeparture() != null ? flightLegApproval.getNewEstimatedDeparture() : flightLegApproval.getNewScheduledDeparture(),
                    flightLegApproval.getNewEstimatedArrival() != null ? flightLegApproval.getNewEstimatedArrival() : flightLegApproval.getNewScheduledArrival());
            if (ows.size() > 0) {
                cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX + flightLegApproval.getFlightLeg().getId() + MSG_CANCELLATION_OVERLAP;
                verifyCancelOneWay(ows, flightLegApproval, cancellationReason);
            }

            //Cancel one Way in case changed previous flight changed the aircraft
            if(flightLegApproval.getOldAircraft() != null && !flightLegApproval.getCancelled()){
                flightMoved = true;
                cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX + flightLegApproval.getFlightLeg().getId() + MSG_CANCELLATION_AIRCRAFT_CHANGE + flightLegApproval.getNewAircraft().getTailNumber();

                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(flightLegApproval.getOldAircraft().getId(), arrival);
                cancelOneWayInCaseChangePreviousEvent(flightLegApproval, aircraftNextEventDto, flightLegApproval.getOldAircraft().getId(), cancellationReason);
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(flightLegApproval.getNewAircraft().getId(),arrival);
                cancelOneWayInCaseChangePreviousEvent(flightLegApproval, aircraftNextEventDto, flightLegApproval.getNewAircraft().getId(), cancellationReason);
            }

            //Cancel One Way in case flight changed departure date and has has different airport than the offer
            if(!flightLegApproval.getCancelled() && flightLegApproval.getOldScheduledDeparture()!= null && !flightLegApproval.getOldScheduledDeparture().equals(flightLegApproval.getNewScheduledDeparture()) ){
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(flightLegApproval.getNewAircraft().getId(), arrival);
                List<OneWayOfferDto> ow = oneWayOfferManager.getOneWayInsideSegment(flightLegApproval.getNewAircraft().getId(),
                        flightLegApproval.getNewEstimatedDeparture() != null ? flightLegApproval.getNewEstimatedDeparture() : flightLegApproval.getNewScheduledDeparture(), aircraftNextEventDto.getDate());

                if(!ow.isEmpty() && ow.get(0).getDepartureAirport()!= null &&!ow.get(0).getDepartureAirport().getId().equals(flightLegApproval.getNewArrivalAirport().getId())){
                    flightMoved = true;
                    cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX + flightLegApproval.getFlightLeg().getId() + MSG_CANCELLATION_EVENT_MOVED;
                    cancelOneWayInCaseChangePreviousEvent(flightLegApproval, aircraftNextEventDto, flightLegApproval.getNewAircraft().getId(), cancellationReason);
                }
            }

            //Cancel one Way in case changed previous flight changed the arrival airport
            if(flightLegApproval.getOldArrivalAirport() != null && !flightLegApproval.getCancelled()){
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(flightLegApproval.getNewAircraft().getId(), arrival);
                cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX + flightLegApproval.getFlightLeg().getId() + MSG_CANCELLATION_FLIGHT_ARRIVAL_AIRPORT_CHANGE + flightLegApproval.getNewArrivalAirport().getIcao();
                cancelOneWayInCaseChangePreviousEvent(flightLegApproval, aircraftNextEventDto, flightLegApproval.getNewAircraft().getId(), cancellationReason);
            }

            //Cancel in case Flight leg is cancelled
            if(flightLegApproval.getCancelled() && flightLegApproval.getOldAircraft() != null){
                aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(flightLegApproval.getOldAircraft().getId(), departure);
                if(!(flightLegApproval.getOldArrivalAirport() != null &&
                        flightLegApproval.getOldArrivalAirport().getId() != null &&
                        aircraftPreviousEventDto.getAirport() != null &&
                        flightLegApproval.getOldArrivalAirport().getId().equals(aircraftPreviousEventDto.getAirport().getId()))){
                    aircraftNextEventDto = flightLegManager.getNextAircraftEvent(flightLegApproval.getNewAircraft().getId(), arrival);
                    cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX + flightLegApproval.getFlightLeg().getId() + MSG_CANCELLATION_FLIGHT_CANCELLED;
                    cancelOneWayInCaseChangePreviousEvent(flightLegApproval, aircraftNextEventDto, flightLegApproval.getNewAircraft().getId(), cancellationReason);
                }
            }

            //Cancel in case Flight leg is added
            if(flightLegApproval.getAdded()){
                aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(flightLegApproval.getNewAircraft().getId(), departure);
                if(!(flightLegApproval.getNewArrivalAirport() != null &&
                        flightLegApproval.getNewArrivalAirport().getId() != null &&
                        aircraftPreviousEventDto.getAirport() != null &&
                        flightLegApproval.getNewArrivalAirport().getId().equals(aircraftPreviousEventDto.getAirport().getId()))){
                    aircraftNextEventDto = flightLegManager.getNextAircraftEvent(flightLegApproval.getNewAircraft().getId(), arrival);
                    cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX + flightLegApproval.getFlightLeg().getId() + MSG_CANCELLATION_EVENT_CREATED;
                    cancelOneWayInCaseChangePreviousEvent(flightLegApproval, aircraftNextEventDto, flightLegApproval.getNewAircraft().getId(), cancellationReason);
                }
            }

        } catch (Exception e) {
            LOGGER.warn(String.format("One Way cancelling cannot be processed %s", flightLegApproval.getFlightLeg().getId()));
        }

    }

    private void verifyCancelOneWay(List<OneWayOfferDto> ows, PfaFlightLegApproval flightLegApproval, String cancellationReason) {
        for(OneWayOfferDto ow : ows) {
            // If the leg created is based on the one way we should not cancel the offer
            Integer oneWayIdByFlightApproval = flightLegApproval.getFlightLeg().getRouteLegs().get(0).getOneWay() !=null ? flightLegApproval.getFlightLeg().getRouteLegs().get(0).getOneWay().getId() : 0;
            if (!ow.getId().equals(oneWayIdByFlightApproval)) {
                ow.setCancellationReason(cancellationReason);
                oneWayOfferManager.cancelOneWayOpportunity(ow);
            }
        }
    }

    private void handleEmptyLeg(PfaFlightLegApproval flightLegApproval) {
        try{
            FlightLeg updatedFlightLeg = flightLegApproval.getFlightLeg();
            Integer aircraftId;

            aircraftId = flightLegApproval.getNewAircraft().getId();


            Date departure = updatedFlightLeg.getScheduledDeparture();
            Date arrival = updatedFlightLeg.getScheduledArrival();

            if (updatedFlightLeg.getEstimatedDeparture() != null && departure.after(updatedFlightLeg.getEstimatedDeparture())){
                departure = updatedFlightLeg.getEstimatedDeparture();
            }

            if(updatedFlightLeg.getEstimatedArrival() != null && arrival.before(updatedFlightLeg.getEstimatedArrival())){
                arrival = updatedFlightLeg.getEstimatedArrival();
            }

            AircraftPreviousEventDto aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(aircraftId, departure);
            AircraftNextEventDto aircraftNextEventDto = flightLegManager.getNextAircraftEvent(aircraftId, arrival);

            //These two if bellow is to ignore a ferry if they are previous or next event.
            if(aircraftPreviousEventDto!= null && aircraftPreviousEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
                //Just adding 10 minutes because if not it would get the same ferry as the previous event
                Date date = new DateTime(aircraftPreviousEventDto.getDate()).minusMinutes(10).toDate();
                aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(aircraftId, date);
            }
            if(aircraftNextEventDto!= null && aircraftNextEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
                //Just adding 10 minutes because if not it would get the same ferry as the next event
                Date date = new DateTime(aircraftNextEventDto.getDate()).plusMinutes(10).toDate();
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(aircraftId, date);
            }

            if(aircraftPreviousEventDto == null){
                aircraftPreviousEventDto = new AircraftPreviousEventDto(flightLegApproval.getNewScheduledDeparture(), flightLegApproval.getFlightLeg().getId(), AircraftNextEventDto.FLIGHT);
            }
            if(aircraftNextEventDto == null){
                aircraftNextEventDto = new AircraftNextEventDto(flightLegApproval.getNewScheduledArrival(), flightLegApproval.getFlightLeg().getId(), AircraftNextEventDto.FLIGHT);
            }
            checkAndCancelEmptyLegWhenFlightChanges(flightLegApproval, aircraftPreviousEventDto, aircraftNextEventDto, aircraftId,false);


            if(changedAircraft(flightLegApproval)){
                aircraftId = flightLegApproval.getOldAircraft().getId();
                AircraftPreviousEventDto oldAircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(aircraftId, departure);
                AircraftNextEventDto oldAircraftNextEventDto = flightLegManager.getNextAircraftEvent(aircraftId, arrival);

                //These two if bellow is to ignore a ferry if they are previous or next event.
                if(oldAircraftPreviousEventDto!= null && oldAircraftPreviousEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
                    //Just adding 10 minutes because if not it would get the same ferry as the previous event
                    Date date = new DateTime(oldAircraftPreviousEventDto.getDate()).minusMinutes(10).toDate();
                    oldAircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(aircraftId, date);
                }
                if(oldAircraftNextEventDto!= null && oldAircraftNextEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
                    //Just adding 10 minutes because if not it would get the same ferry as the next event
                    Date date = new DateTime(oldAircraftNextEventDto.getDate()).plusMinutes(10).toDate();
                    oldAircraftNextEventDto = flightLegManager.getNextAircraftEvent(aircraftId, date);
                }

                if(oldAircraftPreviousEventDto == null){
                    oldAircraftPreviousEventDto = new AircraftPreviousEventDto(flightLegApproval.getNewScheduledDeparture(), flightLegApproval.getFlightLeg().getId(), AircraftNextEventDto.FLIGHT);
                }
                if(oldAircraftNextEventDto == null){
                    oldAircraftNextEventDto = new AircraftNextEventDto(flightLegApproval.getNewScheduledArrival(), flightLegApproval.getFlightLeg().getId(), AircraftNextEventDto.FLIGHT);
                }

                checkAndCancelEmptyLegWhenFlightChanges(flightLegApproval, oldAircraftPreviousEventDto, oldAircraftNextEventDto, aircraftId,true);
            }

        } catch (Exception e) {
            LOGGER.warn(String.format("Empty Leg cancelling cannot be processed %s", flightLegApproval.getFlightLeg().getId()));
        }
    }

    private void handleOneWay(Integer maintenanceID, TimelineMaintenanceDto oldMaintenance, Integer changeType) throws VtsException {
        MaintenanceEventDto maintenanceEventDto = maintenanceManager.getMaintenanceEventById(maintenanceID);
        Integer aircraftId = maintenanceEventDto.getAircraft().getId();
        Date endTime = maintenanceEventDto.getEndTime();
        Date startTime = maintenanceEventDto.getStartTime();
        maintenanceMoved = false;
        maintenanceCancelled = false;

        try {
            AircraftPreviousEventDto aircraftPreviousEventDto;
            AircraftNextEventDto aircraftNextEventDto;

            List<OneWayOfferDto> ows;
            String cancellationReason;

            //Cancel One Ways in case maintenance overlaps the one way segment
            ows = oneWayOfferManager.getOneWaysThatShouldBeCancelled(aircraftId, maintenanceEventDto.getStartTime(), maintenanceEventDto.getEndTime());
            if (ows.size() > 0) {
                for (OneWayOfferDto ow : ows) {
                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceID + MSG_CANCELLATION_OVERLAP;
                    ow.setCancellationReason(cancellationReason);
                    oneWayOfferManager.cancelOneWayOpportunity(ow);
                }
            }

            //Cancel one Way in case changed previous maintenance changed the aircraft
            if(oldMaintenance != null && !oldMaintenance.getAircraftId().equals(maintenanceEventDto.getAircraft().getId())){
                maintenanceMoved = true;
                cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceID + MSG_CANCELLATION_AIRCRAFT_CHANGE + maintenanceEventDto.getAircraft().getTailNumber();
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(oldMaintenance.getAircraftId(), endTime);
                cancelOneWayInCaseChangePreviousEvent(oldMaintenance, maintenanceEventDto, aircraftNextEventDto, oldMaintenance.getAircraftId(), cancellationReason);
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(maintenanceEventDto.getAircraft().getId(), endTime);
                cancelOneWayInCaseChangePreviousEvent(oldMaintenance, maintenanceEventDto, aircraftNextEventDto, maintenanceEventDto.getAircraft().getId(), cancellationReason);
            }

            //Cancel one Way in case changed previous maintenance changed the airport
            if(oldMaintenance != null && ((maintenanceEventDto.getAirport() != null && oldMaintenance.getAirportId() != null
                    && !oldMaintenance.getAirportId().equals(maintenanceEventDto.getAirport().getId()))
                    || (oldMaintenance.getAirportId() == null && maintenanceEventDto.getAirport() != null)
                    || (oldMaintenance.getAirportId() != null && maintenanceEventDto.getAirport() == null))) {

                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(maintenanceEventDto.getAircraft().getId(), endTime);
                cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceID + MSG_CANCELLATION_MAINTENANCE_AIRPORT_CHANGE;
                cancellationReason += maintenanceEventDto.getAirport() != null ? maintenanceEventDto.getAirport().getIcao() : "N/A";
                cancelOneWayInCaseChangePreviousEvent(oldMaintenance, maintenanceEventDto, aircraftNextEventDto, maintenanceEventDto.getAircraft().getId(), cancellationReason);
            }

            //cancel one way in case created or deleted an maintenance before it
            if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_NEW) || changeType.equals(UpdatedDtoConstants.DTO_STATUS_DELETED)){
                aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(maintenanceEventDto.getAircraft().getId(), startTime);
                if(maintenanceEventDto.getAirport() != null && aircraftPreviousEventDto != null && !aircraftPreviousEventDto.getAirport().getId().equals(maintenanceEventDto.getAirport().getId())){
                    if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_DELETED)){
                        maintenanceCancelled = true;
                        cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceID + MSG_CANCELLATION_MAINTENANCE_DELETED;
                    } else {
                        cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceID + MSG_CANCELLATION_EVENT_CREATED;
                    }
                    aircraftNextEventDto = flightLegManager.getNextAircraftEvent(maintenanceEventDto.getAircraft().getId(), endTime);
                    cancelOneWayInCaseChangePreviousEvent(oldMaintenance, maintenanceEventDto, aircraftNextEventDto, maintenanceEventDto.getAircraft().getId(), cancellationReason);
                }
            }

        } catch (Exception e) {
            LOGGER.warn(String.format("One Way cancelling cannot be processed %s", maintenanceID));
        }
    }


    private void handleEmptyLeg(Integer maintenanceID, TimelineMaintenanceDto oldMaintenance, Integer changeType) throws VtsException {

        MaintenanceEventDto maintenanceEventDto = maintenanceManager.getMaintenanceEventById(maintenanceID);

        AircraftPreviousEventDto aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(maintenanceEventDto.getAircraft().getId(), maintenanceEventDto.getStartTime());
        AircraftNextEventDto aircraftNextEventDto = flightLegManager.getNextAircraftEvent(maintenanceEventDto.getAircraft().getId(), maintenanceEventDto.getEndTime());

        //These two if bellow is to ignore a ferry if they are previous or next event.
        if(aircraftPreviousEventDto!= null && aircraftPreviousEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
            //Just removing 10 minutes because if not it would get the same ferry as the previous event
            Date date = new DateTime(aircraftPreviousEventDto.getDate()).minusMinutes(10).toDate();
            aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(maintenanceEventDto.getAircraft().getId(), date);
        }
        if(aircraftNextEventDto!= null && aircraftNextEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
            //Just adding 10 minutes because if not it would get the same ferry as the next event
            Date date = new DateTime(aircraftNextEventDto.getDate()).plusMinutes(10).toDate();
            aircraftNextEventDto = flightLegManager.getNextAircraftEvent(maintenanceEventDto.getAircraft().getId(), date);
        }

        if(aircraftPreviousEventDto == null){
            aircraftPreviousEventDto = new AircraftPreviousEventDto(maintenanceEventDto.getEndTime(), maintenanceEventDto.getId(), AircraftNextEventDto.FLIGHT);
        }
        if(aircraftNextEventDto == null){
            aircraftNextEventDto = new AircraftNextEventDto(maintenanceEventDto.getStartTime(), maintenanceEventDto.getId(), AircraftNextEventDto.FLIGHT);
        }

        if(oldMaintenance != null && !maintenanceEventDto.getAircraft().getId().equals(oldMaintenance.getAircraftId())){
            changedAircraft = true;

        } else {
            changedAircraft = false;
        }
        getEmptyLegCheckAndCancel(oldMaintenance, maintenanceEventDto, aircraftPreviousEventDto, aircraftNextEventDto, changeType, false);

        //checking again for cases where maintenance is moved FROM EL`s aircraft
        if(changedAircraft){
            aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(oldMaintenance.getAircraftId(), oldMaintenance.getStartTime());
            aircraftNextEventDto = flightLegManager.getNextAircraftEvent(oldMaintenance.getAircraftId(), oldMaintenance.getEndTime());

            //These two if bellow is to ignore a ferry if they are previous or next event.
            if (aircraftPreviousEventDto!= null &&  aircraftPreviousEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)) {
                //Just removing 10 minutes because if not it would get the same ferry as the previous event
                Date date = new DateTime(aircraftPreviousEventDto.getDate()).minusMinutes(10).toDate();
                aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(oldMaintenance.getAircraftId(), date);
            }
            if (aircraftNextEventDto!= null && aircraftNextEventDto.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)) {
                //Just adding 10 minutes because if not it would get the same ferry as the next event
                Date date = new DateTime(aircraftNextEventDto.getDate()).plusMinutes(10).toDate();
                aircraftNextEventDto = flightLegManager.getNextAircraftEvent(oldMaintenance.getAircraftId(), date);
            }

            if(aircraftPreviousEventDto == null){
                aircraftPreviousEventDto = new AircraftPreviousEventDto(oldMaintenance.getEndTime(), oldMaintenance.getId(), AircraftNextEventDto.FLIGHT);
            }
            if(aircraftNextEventDto == null){
                aircraftNextEventDto = new AircraftNextEventDto(oldMaintenance.getStartTime(), oldMaintenance.getId(), AircraftNextEventDto.FLIGHT);
            }

            getEmptyLegCheckAndCancel(oldMaintenance, maintenanceEventDto, aircraftPreviousEventDto, aircraftNextEventDto, changeType, true);
        }

//        cacheMaintenance(oldMaintenance, maintenanceEventDto);
    }

    private void checkAndCancelEmptyLegWhenFlightChanges(PfaFlightLegApproval flightLegApproval, AircraftPreviousEventDto aircraftPreviousEventDto, AircraftNextEventDto aircraftNextEventDto, Integer aircraftId,Boolean isChangingFromAircraft) throws VtsException {
        Integer emptyLegId = flightLegApproval.getFlightLeg().getRouteLegs().get(0).getEmptyLeg() !=null ? flightLegApproval.getFlightLeg().getRouteLegs().get(0).getEmptyLeg().getId() : 0;
        if(aircraftPreviousEventDto != null && aircraftNextEventDto != null) {
            List<EmptyLegOfferDto> emptyLegs = emptyLegOfferManager.getEmptyLegsInsideSegment(aircraftId, aircraftPreviousEventDto.getDate(), aircraftNextEventDto.getDate());
            if(emptyLegs.isEmpty()){
                /*
                   this instruction is executed to find a el
                   that was created by a ferry that is after the interval
                   between the new leg approval and the ferry
                 */
                EmptyLegOfferDto elo = emptyLegOfferManager.getEmptyLegByFerryId(aircraftNextEventDto.getEventId());
                if(elo != null && (elo.getOfferStatus().getId().equals(OfferStatusDto.ADVERTISED) ||
                        elo.getOfferStatus().getId().equals(OfferStatusDto.NEW))){
                    /*
                       when new or advertised the elo is aded to emptyLegs
                       array in order to be checked according to el cancelation scenarios
                    */
                    emptyLegs.add(elo);
                }
            }
            if (emptyLegs.size() > 0) {
                for (EmptyLegOfferDto el : emptyLegs) {
                    if (OfferStatusDto.RESERVED != el.getOfferStatus().getId() && OfferStatusDto.SOLD != el.getOfferStatus().getId()) { // RESERVED and SOLD should not be cancelled
                        if (!emptyLegId.equals(el.getId())) {
                            Integer flightTime = 0;
                            if(el.getSegArrivalAirport() != null && el.getSegArrivalAirport().getId() != null){
                                flightTime = emptyLegOfferService.getFerryTime(el, el.getSegDepartureAirport(), el.getSegArrivalAirport().getId(), el.getSegArrivalAirport().getLatitude(), el.getSegArrivalAirport().getLongitude());
                            }
                            if (flightLegApproval.getNewScheduledDeparture().getTime() < el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime()) {
                                Date arrDate = flightLegApproval.getNewEstimatedArrival();
                                if (arrDate == null) {
                                    arrDate = flightLegApproval.getNewScheduledArrival();
                                }
                                if(isChangingFromAircraft){
                                    checkAndCancelPreviousAirportMismatchEL(el, aircraftPreviousEventDto, aircraftNextEventDto, flightTime, flightLegApproval);
                                }else{
                                    AirportDto previousAirportDto = new AirportDto(flightLegApproval.getNewArrivalAirport().getId(),flightLegApproval.getNewArrivalAirport().getIcao());
                                    checkAndCancelPreviousAirportMismatchEL(el, new AircraftPreviousEventDto(arrDate,previousAirportDto), aircraftNextEventDto, flightTime, flightLegApproval);
                                }

                            } else {
                                Date departure = flightLegApproval.getNewEstimatedDeparture();
                                if (departure == null) {
                                    departure = flightLegApproval.getNewScheduledDeparture();
                                }

                                if(isChangingFromAircraft){
                                    checkAndCancelNextAirportMismatchEL(el, aircraftPreviousEventDto, aircraftNextEventDto, flightTime, flightLegApproval);
                                }else{
                                    AirportDto nextAirportDto = new AirportDto(flightLegApproval.getNewDepartureAirport().getId(),flightLegApproval.getNewDepartureAirport().getIcao());
                                    checkAndCancelNextAirportMismatchEL(el, aircraftPreviousEventDto, new AircraftNextEventDto(departure,nextAirportDto.getId()), flightTime, flightLegApproval);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void getEmptyLegCheckAndCancel(TimelineMaintenanceDto oldMaintenance, MaintenanceEventDto maintenanceEventDto, AircraftPreviousEventDto aircraftPreviousEventDto, AircraftNextEventDto aircraftNextEventDto, Integer changeType, Boolean isChangingFromAircraft) throws VtsException {
        String cancellationReason;

        if(aircraftPreviousEventDto != null && aircraftNextEventDto != null){
            List<EmptyLegOfferDto> airportMismatchELs;
            if(isChangingFromAircraft){
                airportMismatchELs = emptyLegOfferManager.getEmptyLegsInsideSegment(oldMaintenance.getAircraftId(), aircraftPreviousEventDto.getDate(), aircraftNextEventDto.getDate());
            }else{
                airportMismatchELs = emptyLegOfferManager.getEmptyLegsInsideSegment(maintenanceEventDto.getAircraft().getId(), aircraftPreviousEventDto.getDate(), aircraftNextEventDto.getDate());
            }
            if(airportMismatchELs.size() > 0) {
                for(EmptyLegOfferDto airportMismatchEL : airportMismatchELs) {
                    if (OfferStatusDto.RESERVED != airportMismatchEL.getOfferStatus().getId() && OfferStatusDto.SOLD != airportMismatchEL.getOfferStatus().getId()) { // RESERVED and SOLD should not be cancelled
                        Integer flightTime = emptyLegOfferService.getFerryTime(airportMismatchEL, airportMismatchEL.getSegDepartureAirport(), airportMismatchEL.getSegArrivalAirport().getId(), airportMismatchEL.getSegArrivalAirport().getLatitude(), airportMismatchEL.getSegArrivalAirport().getLongitude());
                        if(airportMismatchEL.getEmptyLegRoutes() != null && !airportMismatchEL.getEmptyLegRoutes().isEmpty()) {
                            if (airportMismatchEL.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() < maintenanceEventDto.getStartTime().getTime()) {
                                cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + "[TYPE]" + MSG_BREAK_MAX_DEPARTURE;
                                checkAndCancelEmptyLeg(airportMismatchEL, aircraftPreviousEventDto, new AircraftNextEventDto(maintenanceEventDto.getStartTime(), maintenanceEventDto.getId(), maintenanceEventDto.getMaintenanceType().getId()), flightTime, oldMaintenance, maintenanceEventDto, changeType, cancellationReason);
                            } else {
                                cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + "[TYPE]" + MSG_BREAK_MIN_DEPARTURE;
                                // if true the leg is moved from aircraft and the moved leg previous event needs to be checked
                                if (isChangingFromAircraft) {
                                    checkAndCancelEmptyLeg(airportMismatchEL, aircraftPreviousEventDto, aircraftNextEventDto, flightTime, oldMaintenance, maintenanceEventDto, changeType, cancellationReason);
                                } else { // otherwise the moved leg needs to be checked
                                    checkAndCancelEmptyLeg(airportMismatchEL, new AircraftPreviousEventDto(maintenanceEventDto.getEndTime()), aircraftNextEventDto, flightTime, oldMaintenance, maintenanceEventDto, changeType, cancellationReason);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void cacheMaintenance(TimelineMaintenanceDto oldMaintenance, MaintenanceEventDto maintenanceEventDto) {
        if(oldMaintenance == null){
            oldMaintenance = new TimelineMaintenanceDto();
        }
        oldMaintenance.setId(maintenanceEventDto.getId());
        oldMaintenance.setAircraftId(maintenanceEventDto.getAircraft().getId());
        oldMaintenance.setCategoryId(maintenanceEventDto.getCategoryId());
        oldMaintenance.setDispatchable(maintenanceEventDto.getDispatchable());
        oldMaintenance.setLastUpdated(new Date());
        oldMaintenance.setRemarks(maintenanceEventDto.getRemarks());
        oldMaintenance.setEndTime(maintenanceEventDto.getEndTime());
        oldMaintenance.setStartTime(maintenanceEventDto.getStartTime());
        oldMaintenance.setMaintenanceTypeId(maintenanceEventDto.getMaintenanceType().getId());
        oldMaintenance.setMaintenanceTypeName(maintenanceEventDto.getMaintenanceType().getName());
        oldMaintenance.setOrderTypeId((maintenanceEventDto.getOrderType() != null) ? maintenanceEventDto.getOrderType().getId() : null);
        oldMaintenance.setCreatorId((maintenanceEventDto.getCreator() != null) ? maintenanceEventDto.getCreator().getId() : null);
        oldMaintenance.setCreatorFirstName((maintenanceEventDto.getCreator() != null) ? maintenanceEventDto.getCreator().getFirstName() : null);
        oldMaintenance.setCreatorLastName((maintenanceEventDto.getCreator() != null) ? maintenanceEventDto.getCreator().getLastName() : null);
        if(maintenanceEventDto.getAirport() != null) {
            oldMaintenance.setAirportId(maintenanceEventDto.getAirport().getId());
            oldMaintenance.setAirportIcao(maintenanceEventDto.getAirport().getIcao());
            oldMaintenance.setAirportName(maintenanceEventDto.getAirport().getName());
        } else {
            oldMaintenance.setAirportId(null);
            oldMaintenance.setAirportIcao(null);
            oldMaintenance.setAirportName(null);
        }
        cacheHelper.cacheMaintenanceEvent(oldMaintenance);
    }

    private void checkAndCancelPreviousAirportMismatchEL(EmptyLegOfferDto el, AircraftPreviousEventDto pe, AircraftNextEventDto ne, Integer flightTime, PfaFlightLegApproval flightLegApproval) throws VtsException {
        Date departure = (flightLegApproval.getNewEstimatedDeparture() != null) ? flightLegApproval.getNewEstimatedDeparture() : flightLegApproval.getNewScheduledDeparture();
        String cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX+flightLegApproval.getFlightLeg().getId();
        String breakMessage;
        Integer turnaround;

        EmptyLegConfigAvailabilityDto config = emptyLegOfferManager.getEmptyLegConfigAvailabilityByOperator(el.getAircraft().getOperatingCompanyId());
        if(config != null){
            turnaround = config.getTurnaroundTime();
        } else {
            turnaround = emptyLegOfferManager.getEmptyLegConfigAvailabilityByOperator(CompanyDto.VJ_ID).getTurnaroundTime();
        }

        EmptyLegRouteDto elr = el.getEmptyLegRoutes().get(0);
        if (elr.getMinDepartureTime().getTime() < departure.getTime()) {
            breakMessage = MSG_BREAK_MAX_DEPARTURE;
        } else {
            breakMessage = MSG_BREAK_MIN_DEPARTURE;
        }
        // this variable stores a boolean value regarding the EL previous event airport
        Boolean isArrivalAirportChanged =  flightLegApproval.getNewArrivalAirport() != null && flightLegApproval.getOldArrivalAirport() != null
                                        && !(flightLegApproval.getNewArrivalAirport().getId().equals(flightLegApproval.getOldArrivalAirport().getId()));

        if(changedAircraft(flightLegApproval) || isArrivalAirportChanged || flightLegApproval.getCancelled()){
            if(changedAircraft(flightLegApproval)){
                // the first statement checks if the min departure rule is not broken
                if(!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaround, flightTime) && pe.getAirport() != null){
                    cancellationReason = cancellationReason+ MSG_CANCELLATION_EVENT_MOVED + breakMessage;
                    cancelEmptyLeg(el,cancellationReason);
                    return;
                }else{
                    // this statement checkes if elr departure airport and the EL previous event matches
                    if(pe.getAirport() == null || !elr.getDepartureAirport().getId().equals(pe.getAirport().getId())){
                        cancellationReason = cancellationReason+MSG_CANCELLATION_AIRCRAFT_CHANGE + flightLegApproval.getNewAircraft().getTailNumber() + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                        cancelEmptyLeg(el,cancellationReason);
                        return;
                    }

                }
            }

            /* if the variable isArrivalAirportChanged is true the EL is canceled
             and the reason is referent to the flight arrival airport change */
            if(isArrivalAirportChanged){
                if(!elr.getDepartureAirport().getId().equals(pe.getAirport().getId())){
                    cancellationReason = cancellationReason + MSG_CANCELLATION_FLIGHT_ARRIVAL_AIRPORT_CHANGE + flightLegApproval.getNewArrivalAirport().getIcao() + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                    cancelEmptyLeg(el,cancellationReason);
                    return;
                }

            }

            // this statement below is executed when a flight leg is cancelled
            if(flightLegApproval.getCancelled()){
                AircraftPreviousEventDto previousEvent = flightLegManager.getPreviousAircraftEvent(el.getAircraft().getId(),flightLegApproval.getNewScheduledDeparture());
                if(!previousEvent.getAirport().getId().equals(elr.getDepartureAirport().getId())){
                    cancellationReason = cancellationReason + MSG_CANCELLATION_MAINTENANCE_DELETED  + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                    cancelEmptyLeg(el,cancellationReason);
                    return;
                }
            }
        } else {
            // if statement that checks if the leg was added
            if(flightLegApproval.getAdded()) { // this statement is executed when the flight is created
                // statement that checks if flights breaks or not the EL min departure
                if (!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaround, flightTime)) {
                    cancellationReason = cancellationReason + MSG_CANCELLATION_EVENT_CREATED + breakMessage;
                    cancelEmptyLeg(el, cancellationReason);
                } else {
                    // this statement checks if the flight arrival airport matches the EL Airport
                    if (!elr.getDepartureAirport().getId().equals(flightLegApproval.getNewArrivalAirport().getId())) {
                        cancellationReason = cancellationReason + MSG_CANCELLATION_EVENT_CREATED + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                        cancelEmptyLeg(el, cancellationReason);
                    }
                }
            }else{// this statement is executed when the flight is moved in the same aircraft(it has its time changed)
                emptyLegOfferManager.checkAndCancelEmptyLeg(el, true, null, pe, ne, flightTime, cancellationReason+MSG_CANCELLATION_EVENT_MOVED+breakMessage, checkPreviousEvent,checkNextEvent);
            }

        }
    }

    private void checkAndCancelNextAirportMismatchEL(EmptyLegOfferDto el, AircraftPreviousEventDto pe, AircraftNextEventDto ne, Integer flightTime, PfaFlightLegApproval flightLegApproval) throws VtsException {
        String cancellationReason = MSG_CANCELLATION_FLIGHT_PREFIX+flightLegApproval.getFlightLeg().getId();
        Date departure = (flightLegApproval.getNewEstimatedDeparture() != null) ? flightLegApproval.getNewEstimatedDeparture() : flightLegApproval.getNewScheduledDeparture();
        String breakMessage;
        Integer turnaround;

        EmptyLegConfigAvailabilityDto config = emptyLegOfferManager.getEmptyLegConfigAvailabilityByOperator(el.getAircraft().getOperatingCompanyId());
        if(config != null){
            turnaround = config.getTurnaroundTime();
        } else {
            turnaround = emptyLegOfferManager.getEmptyLegConfigAvailabilityByOperator(CompanyDto.VJ_ID).getTurnaroundTime();
        }

        EmptyLegRouteDto elr = el.getEmptyLegRoutes().get(0);

        if (elr.getMinDepartureTime().getTime() <  departure.getTime()) {
            breakMessage = MSG_BREAK_MAX_DEPARTURE;
        } else {
            breakMessage = MSG_BREAK_MIN_DEPARTURE;
        }

        // this variable stores a boolean value regarding the EL next event airport
        Boolean isDepartureAirportChanged =  flightLegApproval.getNewDepartureAirport() != null && flightLegApproval.getOldDepartureAirport() != null
                && !(flightLegApproval.getNewDepartureAirport().getId().equals(flightLegApproval.getOldDepartureAirport().getId()));


        if(changedAircraft(flightLegApproval) || isDepartureAirportChanged  || flightLegApproval.getCancelled()){
            if(changedAircraft(flightLegApproval)){
                // the first statement checks if the max departure rule is not broken
                if(!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaround, flightTime)){
                    cancellationReason = cancellationReason+ MSG_CANCELLATION_EVENT_MOVED + breakMessage;
                    cancelEmptyLeg(el,cancellationReason);
                    return;
                }else{
                    // this statement checks if elr departure airport and the EL next event matches
                    if(!elr.getArrivalAirport().getId().equals(ne.getAirportId())){
                        cancellationReason = cancellationReason+MSG_CANCELLATION_AIRCRAFT_CHANGE + flightLegApproval.getNewAircraft().getTailNumber() + MSG_NEXT_EVENT_NOT_MATCHING;
                        cancelEmptyLeg(el,cancellationReason);
                        return;
                    }
                }
            }
            /* if the oldDepartureAirport is not null the EL is canceled
             and the reason is referent to the flight departure airport change */
            if(isDepartureAirportChanged){
                if(!elr.getArrivalAirport().getId().equals(ne.getAirportId())){
                    cancellationReason = cancellationReason + MSG_CANCELLATION_FLIGHT_DEPARTURE_AIRPORT_CHANGE + flightLegApproval.getNewDepartureAirport().getIcao() + MSG_NEXT_EVENT_NOT_MATCHING;
                    cancelEmptyLeg(el,cancellationReason);
                    return;
                }
            }
            // this statement below is executed when a flight leg is cancelled
            if(flightLegApproval.getCancelled()){
                AircraftNextEventDto nextEvent = flightLegManager.getNextAircraftEvent(el.getAircraft().getId(),flightLegApproval.getNewScheduledArrival());
                if(!nextEvent.getAirportId().equals(elr.getArrivalAirport().getId())){
                    cancellationReason = cancellationReason + MSG_CANCELLATION_MAINTENANCE_DELETED  + MSG_NEXT_EVENT_NOT_MATCHING;
                    cancelEmptyLeg(el,cancellationReason);
                    return;
                }
            }
        } else {
            // if statement that checks if the leg was moved or created
            if(flightLegApproval.getAdded()) { // this statement is executed when the flight is created
                // statement that checks if flights breaks or not the EL min departure
                if (!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaround, flightTime)) {
                    cancellationReason = cancellationReason + MSG_CANCELLATION_EVENT_CREATED + breakMessage;
                    cancelEmptyLeg(el, cancellationReason);
                } else {
                    // this statement checks if the flight departure airport matches the EL Arrival Airport
                    if (!elr.getArrivalAirport().getId().equals(flightLegApproval.getNewDepartureAirport().getId())) {
                        cancellationReason = cancellationReason + MSG_CANCELLATION_EVENT_CREATED + MSG_NEXT_EVENT_NOT_MATCHING;
                        cancelEmptyLeg(el, cancellationReason);
                    }
                }
            }else{ // this statement is executed when the flight is moved in the same aircraft(it has its time changed)
                emptyLegOfferManager.checkAndCancelEmptyLeg(el, true, null, pe, ne, flightTime, cancellationReason+MSG_CANCELLATION_EVENT_MOVED+breakMessage, checkPreviousEvent,checkNextEvent);
            }
        }
    }


    private void cancelEmptyLeg(EmptyLegOfferDto el, String cancellationReason) throws VtsException {
        el.setCancellationReason(cancellationReason);
        el.setOfferStatus(new OfferStatusDto(OfferStatusDto.CANCELLED));
        emptyLegOfferManager.update(el);
    }

    private void checkAndCancelEmptyLeg(EmptyLegOfferDto el, AircraftPreviousEventDto pe, AircraftNextEventDto ne, Integer flightTime, TimelineMaintenanceDto oldMaintenance, MaintenanceEventDto maintenanceEventDto, Integer changeType, String cancellationReason) throws VtsException {

        checkPreviousEvent = false;
        checkNextEvent = false;
        Integer turnaroundTime;

        EmptyLegConfigAvailabilityDto config = emptyLegOfferManager.getEmptyLegConfigAvailabilityByOperator(el.getAircraft().getOperatingCompanyId());
        if(config != null){
            turnaroundTime = config.getTurnaroundTime();
        } else {
            turnaroundTime = emptyLegOfferManager.getEmptyLegConfigAvailabilityByOperator(CompanyDto.VJ_ID).getTurnaroundTime();
        }

        final String TYPE = getType(changeType);
        String icao;
        if(maintenanceEventDto.getAirport() != null && maintenanceEventDto.getAirport().getIcao() != null){
            icao = maintenanceEventDto.getAirport().getIcao();
        } else {
            icao = "N/A";
        }

        cancellationReason = cancellationReason.replaceAll("\\[TYPE\\]", TYPE);

        if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_DELETED)
                || (changedAircraft
                || (changeType.equals(UpdatedDtoConstants.DTO_STATUS_UPDATED) && !(maintenanceEventDto.getAirport() == null && oldMaintenance.getAirportId() != null)) // case changes maintenance airport to N/A is treated in ELSE statement
                || changeType.equals(UpdatedDtoConstants.DTO_STATUS_NEW)
                || (!changeType.equals(UpdatedDtoConstants.DTO_STATUS_NEW) && maintenanceEventDto != null
                    && maintenanceEventDto.getAirport() != null
                    && !maintenanceEventDto.getAirport().getId().equals(oldMaintenance.getAirportId())))){

            // case maintenance is being created
            if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_NEW)){
                // case created maintenance overlaps the empty leg
                if(!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaroundTime, flightTime)) {
                    cancelEmptyLeg(el, cancellationReason);
                    return;

                }else{// else check if the airports still matching
                    if(maintenanceEventDto.getAirport() != null){
                        if (el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() < maintenanceEventDto.getStartTime().getTime() &&
                                !maintenanceEventDto.getAirport().getId().equals(el.getEmptyLegRoutes().get(0).getArrivalAirport().getId())) {

                            cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_EVENT_CREATED  + MSG_NEXT_EVENT_NOT_MATCHING;
                            cancelEmptyLeg(el, cancellationReason);
                            return;

                        }else if((el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() >= maintenanceEventDto.getStartTime().getTime()) &&
                                !maintenanceEventDto.getAirport().getId().equals(el.getEmptyLegRoutes().get(0).getDepartureAirport().getId())){

                            cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_EVENT_CREATED  + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                            cancelEmptyLeg(el, cancellationReason);
                            return;
                        }
                    }
                    // case the maintenance is not overlapping the flight, there are no actions to be done
                    return;
                }
            }

            if(changedAircraft){
                //case overlap EL
                if (!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaroundTime, flightTime)) {
                    cancelEmptyLeg(el, cancellationReason);
                    return;

                } else if (el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() < maintenanceEventDto.getStartTime().getTime()) {
                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_AIRCRAFT_CHANGE + maintenanceEventDto.getAircraft().getTailNumber() + MSG_NEXT_EVENT_NOT_MATCHING;
                    checkNextEvent = true;
                } else {
                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_AIRCRAFT_CHANGE + maintenanceEventDto.getAircraft().getTailNumber() + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                    checkPreviousEvent = true;
                }

                emptyLegOfferManager.checkAndCancelEmptyLeg(el, false, el.getAircraft().getId(), pe, ne, flightTime, cancellationReason, checkPreviousEvent, checkNextEvent);
            }

            if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_DELETED)){

                if (el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() < maintenanceEventDto.getStartTime().getTime()) {

                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_DELETED  + MSG_NEXT_EVENT_NOT_MATCHING;
                    checkNextEvent = true;

                }else if(el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() >= maintenanceEventDto.getStartTime().getTime()){

                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_DELETED  + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                    checkPreviousEvent = true;
                }

            }

            if(oldMaintenance != null && maintenanceEventDto.getAirport() != null && !maintenanceEventDto.getAirport().getId().equals(oldMaintenance.getAirportId())) {
                if (el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() < maintenanceEventDto.getStartTime().getTime()) {
                    checkNextEvent = true;
                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId()  + MSG_CANCELLATION_MAINTENANCE_AIRPORT_CHANGE + icao + MSG_NEXT_EVENT_NOT_MATCHING;
                    if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_DELETED)){
                        cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_DELETED  + MSG_NEXT_EVENT_NOT_MATCHING;
                    }
                }
                else {
                    checkPreviousEvent = true;
                    cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_AIRPORT_CHANGE + icao + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                    if(changeType.equals(UpdatedDtoConstants.DTO_STATUS_DELETED)){
                        cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_DELETED  + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                    }

                }
            } else if (changeType.equals(UpdatedDtoConstants.DTO_STATUS_UPDATED)) { //last remaining verification is if maintenance has been moved in its current aircraft
                if(!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaroundTime, flightTime)) {
                    cancelEmptyLeg(el, cancellationReason);
                    return;
                }
            }
            emptyLegOfferManager.checkAndCancelEmptyLeg(el, false, el.getAircraft().getId(), pe, ne, flightTime, cancellationReason, checkPreviousEvent, checkNextEvent);

        } else {
            // case changes maintenance airport to N/A
            if(maintenanceEventDto.getAirport() == null && oldMaintenance.getAirportId() != null) {

                if(!emptyLegOfferManager.checkIfMinAndMaxDepartureStillValid(el, pe, ne, turnaroundTime, flightTime)) {
                    cancelEmptyLeg(el, cancellationReason);
                }else {

                    if (el.getEmptyLegRoutes().get(0).getMinDepartureTime().getTime() < maintenanceEventDto.getStartTime().getTime()) {
                        cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_AIRPORT_CHANGE + icao + MSG_NEXT_EVENT_NOT_MATCHING;
                        ne = flightLegManager.getNextAircraftEvent(maintenanceEventDto.getAircraft().getId(),maintenanceEventDto.getEndTime());
                        if(ne != null && !el.getEmptyLegRoutes().get(0).getArrivalAirport().getId().equals(ne.getAirportId())){
                           cancelEmptyLeg(el,cancellationReason);
                        }
                    } else {
                        cancellationReason = MSG_CANCELLATION_MAINTENANCE_PREFIX + maintenanceEventDto.getId() + MSG_CANCELLATION_MAINTENANCE_AIRPORT_CHANGE + icao + MSG_PREVIOUS_EVENT_NOT_MATCHING;
                        pe = flightLegManager.getPreviousAircraftEvent(maintenanceEventDto.getAircraft().getId(),maintenanceEventDto.getStartTime());
                        if(pe != null && !el.getEmptyLegRoutes().get(0).getDepartureAirport().getId().equals(pe.getAirport().getId())){
                            cancelEmptyLeg(el,cancellationReason);
                        }
                    }
                }
            }
        }
    }

    private String getType(Integer changeType) {
        final String TYPE;
        final int NEW = 2;
        final int UPDATED = 3;
        switch(changeType) {
            case NEW:
                TYPE = MSG_CANCELLATION_EVENT_CREATED;
                break;
            case UPDATED:
                TYPE = MSG_CANCELLATION_EVENT_MOVED;
                break;
            default:
                TYPE = MSG_CANCELLATION_EVENT_CREATED;
        }
        return TYPE;
    }

    private void cancelOneWayInCaseChangePreviousEvent(PfaFlightLegApproval flightLegApproval, AircraftNextEventDto aircraftNextEventDto, Integer aircraftId, String cancellationReason) {
        List<OneWayOfferDto> ows;
        if(aircraftNextEventDto == null){
            //When there is no next event put 100 days after a flight to get all one way possibles
            Date date = new DateTime(flightLegApproval.getNewScheduledArrival()).plusDays(100).toDate();
            aircraftNextEventDto = new AircraftNextEventDto(date, flightLegApproval.getFlightLeg().getId(), AircraftNextEventDto.FLIGHT);
        }
        ows = oneWayOfferManager.getOneWayInsideSegment(aircraftId,
                flightLegApproval.getNewEstimatedDeparture() != null ? flightLegApproval.getNewEstimatedDeparture() : flightLegApproval.getNewScheduledDeparture(),
                aircraftNextEventDto.getDate());

        if(ows.size() > 0) {
            cancellationReason += MSG_PREVIOUS_EVENT_NOT_MATCHING;

            List<OneWayOfferDto> oneWaysToCancel = new ArrayList<>();
            for(OneWayOfferDto ow : ows) {
                ow.setCancellationReason(cancellationReason);
                if(flightMoved) {
                    if (flightLegApproval.getNewAircraft().getId().equals(ow.getAircraftId())) {
                        //case flight has been moved to owe way's aircraft
                        if(flightLegApproval.getNewArrivalAirport() != null && flightLegApproval.getNewArrivalAirport().getId() != ow.getDepartureAirport().getId()){
                            oneWaysToCancel.add(ow);
                        }
                    } else {
                        //case flight has been moved from one way's aircraft
                        try {
                            Date oldStateDepDate = getOldStateDepDate(flightLegApproval);
                            AircraftPreviousEventDto aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(flightLegApproval.getOldAircraft().getId(), oldStateDepDate);
                            if(aircraftPreviousEventDto.getAirport() != null && aircraftPreviousEventDto.getAirport().getId() != ow.getDepartureAirport().getId()){
                                oneWaysToCancel.add(ow);
                            }
                        } catch (VtsException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    oneWaysToCancel.add(ow);
                }
            }

            verifyCancelOneWay(oneWaysToCancel, flightLegApproval, cancellationReason);
        }
    }

    private Date getOldStateDepDate(PfaFlightLegApproval flightLegApproval) {
        Date oldStateDepDate = flightLegApproval.getOldEstimatedDeparture() != null ? flightLegApproval.getOldEstimatedDeparture() : flightLegApproval.getOldScheduledDeparture();

        // if we do not have time changes, old estimated and old scheduled times will be null in PfaFlightLegApproval object, so we need to get the new ones
        if (oldStateDepDate == null) {
            oldStateDepDate = flightLegApproval.getNewEstimatedDeparture() != null ? flightLegApproval.getNewEstimatedDeparture() : flightLegApproval.getNewScheduledDeparture();
        }

        return oldStateDepDate;
    }

    private void cancelOneWayInCaseChangePreviousEvent(TimelineMaintenanceDto oldMaintenance, MaintenanceEventDto maintenanceEventDto, AircraftNextEventDto aircraftNextEventDto, Integer aircraftId, String cancellationReason) {
        List<OneWayOfferDto> ows;
        if(aircraftNextEventDto == null){
            //When there is no next event put 100 days after a maintenance to get all one way possibles
            Date date = new DateTime(maintenanceEventDto.getEndTime()).plusDays(100).toDate();
            aircraftNextEventDto = new AircraftNextEventDto(date, maintenanceEventDto.getId(), AircraftNextEventDto.FLIGHT);
        }
        ows = oneWayOfferManager.getOneWayInsideSegment(aircraftId, maintenanceEventDto.getStartTime(), aircraftNextEventDto.getDate());
        if(ows.size() > 0) {
            cancellationReason += MSG_PREVIOUS_EVENT_NOT_MATCHING;

            for(OneWayOfferDto ow : ows) {
                ow.setCancellationReason(cancellationReason);
                if(!maintenanceMoved && (maintenanceEventDto.getAirport() != null && ow.getDepartureAirport().getId() != maintenanceEventDto.getAirport().getId())) {
                    oneWayOfferManager.cancelOneWayOpportunity(ow);

                } else if(!maintenanceMoved && ((maintenanceEventDto.getAirport() == null && oldMaintenance.getAirportId() != null) || maintenanceCancelled)) {
                    try {
                        AircraftPreviousEventDto aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(maintenanceEventDto.getAircraft().getId(), maintenanceEventDto.getStartTime());
                        if(aircraftPreviousEventDto.getAirport() != null && aircraftPreviousEventDto.getAirport().getId() != ow.getDepartureAirport().getId()){
                            oneWayOfferManager.cancelOneWayOpportunity(ow);
                        }
                    } catch (VtsException e) {
                        e.printStackTrace();
                    }

                } else if(maintenanceMoved) {
                    if (maintenanceEventDto.getAircraft().getId().equals(ow.getAircraftId())) {
                        //case maintenance has been moved to owe way's aircraft
                        if(maintenanceEventDto.getAirport() != null && maintenanceEventDto.getAirport().getId() != ow.getDepartureAirport().getId()){
                            oneWayOfferManager.cancelOneWayOpportunity(ow);
                        }
                    } else {
                        //case maintenance has been moved from one way's aircraft
                        try {
                            AircraftPreviousEventDto aircraftPreviousEventDto = flightLegManager.getPreviousAircraftEvent(oldMaintenance.getAircraftId(), oldMaintenance.getStartTime());
                            if(aircraftPreviousEventDto.getAirport() != null && aircraftPreviousEventDto.getAirport().getId() != ow.getDepartureAirport().getId()){
                                oneWayOfferManager.cancelOneWayOpportunity(ow);
                            }
                        } catch (VtsException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handleFlightLegUpdate(CrewUpdateEvent crewUpdateEvent) throws VtsException {
    }

    @Override
    public void handleFlightLegUpdate(PassengersUpdateEvent passengersUpdateEvent) throws VtsException {
    }

    @Override
    public void handleFlightLegUpdate(NumberOfPassengersChangeEvent paxNumberUpdateEvent) throws VtsException {
    }

    @Override
    public void handleFlightLegUpdate(PrefUpdateEvent prefUpdateEvent) throws VtsException {
    }

    @Override
    public void handleFlightLegUpdate(FBOChangeEvent fBOChangeEvent) throws VtsException {

    }

    @Override
    public void handleFlightLegUpdate(PaxCateringPrefsUpdateEvent paxCateringPrefsUpdateEvent) throws VtsException {

    }

    @Override
    public void handleFlightOrderAtomicUpdate(FlightOrderAtomicUpdateEvent flightOrderAtomicUpdateEvent) throws VtsException {
        final OrderBusinessStatusId orderBusinessStatusId = flightOrderAtomicUpdateEvent.getFlightOrderDescriptor().getOrderBusinessStatusId();
        if (!orderBusinessStatusId.equals(OrderBusinessStatusId.CANCELLED)) {
            return;
        } else {
            //Back EL or OW offerStatus to Advertised
            List<RouteLeg> routeLegs = routeLegManager.retrieveRouteLegs(flightOrderAtomicUpdateEvent.getFlightOrderDescriptor().getFlightOrderId());
            for(RouteLeg routeLeg : routeLegs){
                if (routeLeg.getActive() && routeLeg.getEmptyLeg() != null && (routeLeg.getEmptyLeg().getOfferStatus().getId().equals(OfferStatusDto.RESERVED)||routeLeg.getEmptyLeg().getOfferStatus().getId().equals(OfferStatusDto.SOLD))) {
                    if(routeLeg.getEmptyLeg().getWasAdvertised() == true){
                        emptyLegOfferManager.setOfferStatus(routeLeg.getEmptyLeg().getId(), OfferStatusDto.ADVERTISED, OfferStatusDto.OFFER_STATUS_ADVERTISED_NAME);
                    } else {
                        emptyLegOfferManager.setOfferStatus(routeLeg.getEmptyLeg().getId(), OfferStatusDto.NEW, OfferStatusDto.OFFER_STATUS_NEW_NAME);
                    }
                }else if (routeLeg.getActive() && routeLeg.getOneWay() != null && (routeLeg.getOneWay().getOfferStatus().getId().equals(OfferStatusDto.RESERVED)||routeLeg.getOneWay().getOfferStatus().getId().equals(OfferStatusDto.SOLD))) {
                    oneWayOfferManager.setOfferStatus(routeLeg.getOneWay().getId(), OfferStatusDto.ADVERTISED, OfferStatusDto.OFFER_STATUS_ADVERTISED_NAME);
                }
            }
        }
    }

    @Override
    public void handleFlightOrderUpdate(FlightOrderUpdateEvent updateEvent) throws VtsException {
    }

    @Override
    public ObserverParameters getParameters() {
        return OBSERVER_PARAMETERS;
    }


    @Override
    public void handleTimelineNotes(NoteChangeObject co) {
        try {
            TimelineNoteDto noteDto = aircraftScheduleNoteManager.retrieve(co.getNoteId());
            // this array receives all elo that overlaps the note
            List<EmptyLegOfferDto> overlappinEL =
                    emptyLegOfferManager.getOverllapingEmptyLegs(noteDto.getAircraftId(), noteDto.getFromDate(), noteDto.getToDate());
            if (!overlappinEL.isEmpty()) {
                // if the note states that crew is not available, then the elo is cancelled
                if (noteDto.getCrewNotAvailable()) {
                    for(EmptyLegOfferDto elo : overlappinEL){
                        cancelEmptyLeg(elo,MSG_CREW);
                    }
                }
            }
        } catch (VtsException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void handleAircraftAvailabilityEvents(AircraftAvailabilityChangeObject co) {
        AircraftAvailabilityDto aircraftAvailabilityDto = aircraftAvailabilityManager.getAircraftAvailabilityById(co.getAircraftAvailabilityId());
        List<OneWayOffer> overlappingOWOffers = new ArrayList<>();
        List<EmptyLegOffer> overlappingELOffers = new ArrayList<>();
        AircraftPreviousEventDto previousEvent = null;
        AircraftNextEventDto nextEvent = null;
        // if the aircraft event is still activated then the normal cancellation flow
        if (co.getChangeType().equals(UpdatedDtoConstants.DTO_STATUS_NEW)) {
            overlappingOWOffers = oneWayOfferManager.getOverlappingOneWaysOnAircraftEvents(aircraftAvailabilityDto);
            overlappingELOffers = emptyLegOfferManager.getOverllpingEmptyLegsOnAircraftEvents(aircraftAvailabilityDto);

            // when there is any overlapping offer the method will be executed
            if (!overlappingOWOffers.isEmpty()) {
                cancelOverlappingOWOs(overlappingOWOffers, aircraftAvailabilityDto);
            }

            // when there is any overlapping offer the method will be executed
            if (!overlappingELOffers.isEmpty()) {
                cancelOverlappingELOs(overlappingELOffers, aircraftAvailabilityDto);
            }
        } else { // this else statement will be executed when any update occur

            if (aircraftAvailabilityDto.getActive()) {
                AircraftAvailability oldAircraftAvailability = co.getOldAircraftAvailability();
                AircraftAvailabilityDto oldAircraftAvailabilityDto = new AircraftAvailabilityDto();
                Integer oldAircraftId = oldAircraftAvailability.getAircraft() != null ? oldAircraftAvailability.getAircraft().getId() : -1;
                Integer currentAircraftId = aircraftAvailabilityDto.getTailId() != null ? aircraftAvailabilityDto.getTailId() : -1;
                String oldTailNumber = oldAircraftAvailability.getAircraft() != null ? oldAircraftAvailability.getAircraft().getTailNumber() : null;
                if(!aircraftAvailabilityDto.getAircraftTypeId().equals(oldAircraftAvailability.getAircraftType().getId()) ||
                        aircraftAvailabilityDto.getTailId() != null && !currentAircraftId.equals(oldAircraftId)
                    ){

                    oldAircraftAvailabilityDto = new AircraftAvailabilityDto(aircraftAvailabilityDto.getId(),
                                                                             oldTailNumber,
                                                                             oldAircraftAvailability.getAircraftType().getName(),
                                                                             oldAircraftAvailability.getAircraftType().getId(),
                                                                             oldAircraftId,
                                                                             oldAircraftAvailability.getStartDate(),
                                                                             oldAircraftAvailability.getEndDate(),
                                                                             oldAircraftAvailability.getIsActive());

                       checkAndCalcelOffersUnderAircraftPeriod(oldAircraftAvailabilityDto);
                       checkAndCalcelOffersUnderAircraftPeriod(aircraftAvailabilityDto);
                }
                if(!aircraftAvailabilityDto.getStartDate().equals(oldAircraftAvailability.getStartDate()) ||
                        !aircraftAvailabilityDto.getEndDate().equals(oldAircraftAvailability.getEndDate())){
                           oldAircraftAvailabilityDto = new AircraftAvailabilityDto(aircraftAvailabilityDto.getId(),
                                   oldTailNumber,
                                   oldAircraftAvailability.getAircraftType().getName(),
                                   oldAircraftAvailability.getAircraftType().getId(),
                                   oldAircraftId,
                                   oldAircraftAvailability.getStartDate(),
                                   oldAircraftAvailability.getEndDate(),
                                   oldAircraftAvailability.getIsActive());

                    checkAndCalcelOffersUnderAircraftPeriod(oldAircraftAvailabilityDto);
                    checkAndCalcelOffersUnderAircraftPeriod(aircraftAvailabilityDto);
                }
            }else{
                checkAndCalcelOffersUnderAircraftPeriod(aircraftAvailabilityDto);
            }
        }
    }

    public void checkAndCalcelOffersUnderAircraftPeriod(AircraftAvailabilityDto aircraftAvailabilityDto){
        List<OneWayOffer> overlappingOWOffers = new ArrayList<>();
        List<EmptyLegOffer> overlappingELOffers = new ArrayList<>();
        AircraftPreviousEventDto previousEvent = null;
        AircraftNextEventDto nextEvent = null;
        try{
            if(aircraftAvailabilityDto.getTailNumber() != null) {
                previousEvent = flightLegManager.getPreviousAircraftEvent(aircraftAvailabilityDto.getTailId(), aircraftAvailabilityDto.getStartDate());
                aircraftAvailabilityDto.setStartDate(previousEvent.getDate());
                if(aircraftAvailabilityDto.getEndDate() != null){
                    nextEvent = flightLegManager.getNextAircraftEvent(aircraftAvailabilityDto.getTailId(),aircraftAvailabilityDto.getEndDate());
                    if(nextEvent != null){
                        // if the next event is a ferry it must be skipped and the immediate next event will be fetched
                        if(nextEvent.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)){
                            Date date  = new DateTime(nextEvent.getDate()).plusMinutes(10).toDate();
                            nextEvent = flightLegManager.getNextAircraftEvent(aircraftAvailabilityDto.getTailId(),date);
                        }
                        aircraftAvailabilityDto.setEndDate(nextEvent.getDate());
                    }
                }
                overlappingOWOffers = oneWayOfferManager.getOverlappingOneWaysOnAircraftEvents(aircraftAvailabilityDto);
                if(!overlappingOWOffers.isEmpty()){
                    cancelOverlappingOWOs(overlappingOWOffers,aircraftAvailabilityDto);
                }

                overlappingELOffers = emptyLegOfferManager.getOverllpingEmptyLegsOnAircraftEvents(aircraftAvailabilityDto);
                if(!overlappingELOffers.isEmpty()){
                    cancelOverlappingELOs(overlappingELOffers,aircraftAvailabilityDto);
                }
            }else{
                    /*
                       when an aircraft event without tail is cancelled a query
                       to fetch all tails regarding a type must be executed
                    */
                List<AircraftDto> tails = aircraftManager.getTailByAircraftType(aircraftAvailabilityDto.getAircraftTypeId());

                // the start and end date are declared in order to keep the Aircraft Events values along the iteration
                Date start = aircraftAvailabilityDto.getStartDate();
                Date end = null;

                if(aircraftAvailabilityDto.getEndDate() != null){
                    end = aircraftAvailabilityDto.getEndDate();
                }

                    for(AircraftDto tail : tails){
                        previousEvent = flightLegManager.getPreviousAircraftEvent(tail.getId(), start);
                        if (previousEvent != null) {
                            aircraftAvailabilityDto.setStartDate(previousEvent.getDate());
                            if(aircraftAvailabilityDto.getEndDate() != null) {
                                nextEvent = flightLegManager.getNextAircraftEvent(tail.getId(), end);
                                if (nextEvent != null) {
                                    if (nextEvent.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)) {
                                        Date date = new DateTime(nextEvent.getDate()).plusMinutes(10).toDate();
                                        nextEvent = flightLegManager.getNextAircraftEvent(tail.getId(), date);
                                    }
                                    aircraftAvailabilityDto.setEndDate(nextEvent.getDate());
                                }
                            }
                        }

                        /*
                          the aircraftAvailabilityDto gets the Tail Number Value
                          in order to fetch the offers of current tail
                        */
                    aircraftAvailabilityDto.setTailNumber(tail.getTailNumber());
                    overlappingOWOffers = oneWayOfferManager.getOverlappingOneWaysOnAircraftEvents(aircraftAvailabilityDto);
                    overlappingELOffers = emptyLegOfferManager.getOverllpingEmptyLegsOnAircraftEvents(aircraftAvailabilityDto);

                    if(!overlappingOWOffers.isEmpty()){
                        // tailNumber is set to null because its the flag to get the according message
                        aircraftAvailabilityDto.setTailNumber(null);
                        cancelOverlappingOWOs(overlappingOWOffers,aircraftAvailabilityDto);
                    }

                    if(!overlappingELOffers.isEmpty()){
                        // tailNumber is set to null because its the flag to get the according message
                        aircraftAvailabilityDto.setTailNumber(null);
                        cancelOverlappingELOs(overlappingELOffers,aircraftAvailabilityDto);
                    }
                }
            }
        }catch(VtsException ex){
            ex.printStackTrace();
        }
    }

    // this method is responsible for cancelling the overlapping owo as well as setting a cancellation reason message on them
    private void cancelOverlappingOWOs(List<OneWayOffer> offers, AircraftAvailabilityDto aircraftAvailabilityDto){
        String cancellationReason = getAircraftEventCancellationReason(aircraftAvailabilityDto);
        // a certain message description will be applied according to the presence of a tail number
        oneWayOfferManager.cancelOneWayOffers(offers, cancellationReason);
    }

    // this method is responsible for cancelling the overlapping elo as well as setting a cancellation reason message on them
    private void cancelOverlappingELOs(List<EmptyLegOffer> offers, AircraftAvailabilityDto aircraftAvailabilityDto){
        String cancellationReason = getAircraftEventCancellationReason(aircraftAvailabilityDto);
        // a certain message description will be applied according to the presence of a tail number
        emptyLegOfferManager.cancelEmptyLegs(offers, cancellationReason);
    }

    private String getAircraftEventCancellationReason(AircraftAvailabilityDto aircraftAvailabilityDto){
        String cancellationReason = "";
        if(aircraftAvailabilityDto.getActive()){
            if (aircraftAvailabilityDto.getTailNumber() == null) {
                cancellationReason = "the offer overlaps the " + aircraftAvailabilityDto.getAircraftType() + " " +
                        " aircraft type unavailability period";
            } else {
                cancellationReason = "the offer overlaps the " + aircraftAvailabilityDto.getTailNumber() + " " +
                        " aircraft unavailability period ";
            }
        }else{
            if(aircraftAvailabilityDto.getTailNumber() == null){
                cancellationReason = aircraftAvailabilityDto.getAircraftType() + " " +
                        " aircraft type unavailability period was cancelled";
            }else{
                cancellationReason = aircraftAvailabilityDto.getTailNumber() + " " +
                        " aircraft unavailability period was cancelled";
            }
        }
        return cancellationReason;
    }

    @Override
    public void handleTimelineEventsAvailability(TimelineEventsAvailabilityChangeObject co) {
        final TimelineEvent timelineEvent = timelineEventManager.getTimelineEventById(co.getTimelineEventsId());
        final TimelineEvent oldEvent = co.getOldState();

        if (timelineEvent != null) {
            if (oldEvent == null) { //case when inserting a new event
                if (timelineEvent.getAutoAdvertising()) {
                    LOGGER.info("TimelineEvent id " + timelineEvent.getId() + " is checked to auto advertising. There are no restrictions in this period.");
                } else {
                    cancelOffersByEvent(timelineEvent);
                }
            } else if (!timelineEvent.getActive()) { // case when deleting event
                if (timelineEvent.getAutoAdvertising()) {
                    LOGGER.info("TimelineEvent id " + timelineEvent.getId() + " is not active.");
                } else {
                    cancelOffersByEvent(timelineEvent);
                }
            } else { //update event
                if (!timelineEvent.getAutoAdvertising().equals(oldEvent.getAutoAdvertising()) ||
                        !timelineEvent.getStartDate().equals(oldEvent.getStartDate()) ||
                        !timelineEvent.getEndDate().equals(oldEvent.getEndDate())) {

                    if (!oldEvent.getAutoAdvertising()) {
                        cancelOffersByEvent(oldEvent);
                    }
                    if (!timelineEvent.getAutoAdvertising()) {
                        cancelOffersByEvent(timelineEvent);
                    }
                } else {
                    LOGGER.info("TimelineEvent id " + timelineEvent.getId() + " updated without offer cancellations.");
                }
            }
        }
    }

    private void cancelOffersByEvent(TimelineEvent timelineEvent) {
        AircraftPreviousEventDto previousEvent = null;
        AircraftNextEventDto nextEvent = null;

        // get all aircrafts from timeline
        List<AircraftTypeForDropdownDto> allAircraftTypes = aircraftTypeManager.getAircraftTypeForDropDown(new Date());
        List<Integer> aircraftTypeList = new ArrayList<>();
        for (AircraftTypeForDropdownDto type : allAircraftTypes) {
            if (type.getVjOwned()) {
                aircraftTypeList.add(type.getId());
            }
        }
        List<AircraftDto> aircraftDtoList = aircraftManager.getAllAircraftTailNumberOfTypesEx(aircraftTypeList, false, false);

        // the start and end date are declared in order to keep the Timeline Events values along the iteration
        Date start = timelineEvent.getStartDate();
        Date end = null;

        if(timelineEvent.getEndDate() != null){
            end = timelineEvent.getEndDate();
        }
        try {
            for (AircraftDto aircraft : aircraftDtoList) {
                previousEvent = flightLegManager.getPreviousAircraftEvent(aircraft.getId(), start);
                if (previousEvent != null) {
                    timelineEvent.setStartDate(previousEvent.getDate());
                    if (timelineEvent.getEndDate() != null) {
                        nextEvent = flightLegManager.getNextAircraftEvent(aircraft.getId(), end);
                        if (nextEvent != null) {
                            if (nextEvent.getLegBusinessTypeID().equals(LegBusinessTypeDto.OPERATIONAL_FERRY)) {
                                Date date = new DateTime(nextEvent.getDate()).plusMinutes(10).toDate();
                                nextEvent = flightLegManager.getNextAircraftEvent(aircraft.getId(), date);
                            }
                            timelineEvent.setEndDate(nextEvent.getDate());
                        }
                    }
                }

                cancelEmptyLegOfferOverlapTimelineEventPeriod(timelineEvent, aircraft.getId());
                cancelOneWayOfferOverlapTimelineEventPeriod(timelineEvent, aircraft.getId());

            }
        } catch (VtsException e) {
            e.printStackTrace();
        }
    }

    private void cancelEmptyLegOfferOverlapTimelineEventPeriod(TimelineEvent timelineEvent, Integer aircraftId) {
        final List<EmptyLegOffer> emptyLegOffers = emptyLegOfferManager.getEmptyLegOffersValidToBeCancelOverlapPeriod(
                timelineEvent.getStartDate(), timelineEvent.getEndDate(), aircraftId);

        if (emptyLegOffers == null || emptyLegOffers.isEmpty()) {
            LOGGER.info("There are no Empty Leg offer to be cancel.");
        } else {
            final String cancellationReason = "The Empty Leg offer overlap the Timeline Event restriction period";
            emptyLegOfferManager.cancelEmptyLegs(emptyLegOffers, cancellationReason);
        }
    }

    private void cancelOneWayOfferOverlapTimelineEventPeriod(TimelineEvent timelineEvent, Integer aircraftId) {
        final List<OneWayOffer> oneWayOffers = oneWayOfferManager.getOneWayOffersValidToBeCancelOverlapPeriod(timelineEvent.getStartDate(), timelineEvent.getEndDate(), aircraftId);

        if (oneWayOffers == null || oneWayOffers.isEmpty()) {
            LOGGER.info("There are no One Way offer to be cancel.");
        } else {
            final String cancellationReason = "The One Way offer overlap the Timeline Event restriction period";
            oneWayOfferManager.cancelOneWayOffers(oneWayOffers, cancellationReason);
        }
    }

    @Override
    public GVObserverPriority getPriority() {
        return GVObserverPriority.HIGH;
    }
}
