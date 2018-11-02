package com.vistajet.model.dao;


import com.vistajet.model.dto.*;
import com.vistajet.model.dto.availability.AircraftAvailabilityDto;
import com.vistajet.model.dto.identifier.*;
import com.vistajet.model.entity.*;
import com.vistajet.model.exception.VtsException;

import java.util.*;

public interface EmptyLegOfferManager {
    EmptyLegOfferDto saveEmptyLegOffer(EmptyLegOfferDto emptyLegOfferDto) throws VtsException ;
    void saveEmptyLegOffer(EmptyLegOffer emptyLegOffer) throws VtsException ;
    OfferStatusDto getOfferStatus(Integer id);
    void setOfferStatus(Integer emptyLegId, Integer newStatusId, String newStatusName);
    EmptyLegOffer getEmptyLegById(Integer emptyLegId);
    EmptyLegOfferDto getEmptyLegByIdAsDto(Integer emptyLegId);
    EmptyLegOfferDto getEmptyLegAndRoutesByFerryId(Integer flightId);
    EmptyLegOfferDto getEmptyLegByFerryId(Integer flightId);
    EmptyLegOffer getEmptyLegEntityByFerryId(Integer flightId);
    EmptyLegOffer retrieveEmptyLegByFerryId(Integer flightId);
    List<EmptyLegConfigAvailabilityDto> getEmptyLegConfigAvailability();
    EmptyLegConfigAvailabilityDto getEmptyLegConfigAvailabilityByOperator(final Integer operatorId);
    Integer getTurnaroundTime();
    Boolean segmentHasEmptyLeg(Integer aircraftId, Date segmentStart);
    EmptyLegOfferDto getEmptyLegBySegment(Integer aircraftId, Date segmentStart, Date segmentEnd);
    EmptyLegOfferDto getEmptyLegByMinMaxDeparture(Integer aircraftId, Date minDeparture, Date maxDeparture);
    List<EmptyLegOfferDto> getEmptyLegsInsideSegment(Integer aircraftId, Date startDate, Date endDate);
    List<EmptyLegOfferDto> getNotCancelledEmptyLegsInsideSegment(Integer aircraftId, Date startDate, Date endDate);
    List<EmptyLegOfferDto> getCancelledEmptyLegsInsideSegment(Integer aircraftId, Date startDate, Date endDate);
    TimelineEmptyLegOfferDto getTimelineEmptyLeg(Integer id);
    List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersByIds(Set<EmptyLegOfferId> emptyLegIds);
    List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersDtoByIds(Collection<EmptyLegOfferId> emptyLegIds);
    List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersDtoByStatus(Collection<Integer> emptyLegIds);
    List<TimelineEmptyLegOfferDto> getTimelineEmptyLegOffersByDate(Date startTime, Date endTime);
    List<EmptyLegOffer> getEmptyLegOffersValidToBeCancelOverlapPeriod(Date startTime, Date endTime, Integer aircraftId);
    void cancelEmptyLegs(List<EmptyLegOffer> emptyLegOffers, String cancellationReason);
    List<EmptyLegOfferDto> retrieveValidEmptyLegOffers(EmptyLegOfferFilterDto emptyLegOfferFilterDto, Boolean filterByStatus);
    List<EmptyLegOfferDto> retrieveValidEmptyLegOffers(ArrayList<Integer> statusList);
    List<Integer> retrieveFlighLegIdFromEmptyLegOffers(EmptyLegOfferFilterDto emptyLegOfferFilterDto);
    EmptyLegRouteDto loadEmptyLegRouteToQuote(Integer emptyLegRouteId)  throws VtsException;
    void inactiveEmptyLegRoutes(List<EmptyLegRouteDto> emptyLegRoutesRemovedDtos)  throws VtsException;
    void inactiveEmptyLegRoutesByFind(List<EmptyLegRouteDto> emptyLegRoutesRemovedDtos)  throws VtsException;
    void saveAircraftConfigAvailability(List<EmptyLegConfigAvailabilityDto> dtos) throws VtsException;
    Boolean CheckIfEmptyLegIsActivated(Integer emptyLegId) throws VtsException;
    void checkAndCancelEmptyLeg(EmptyLegOfferDto emptyLegOffer, Boolean needToCheckMinMaxDeparture, Integer newAircraftID, AircraftPreviousEventDto aircraftPreviousEventDto, AircraftNextEventDto aircraftNextEventDto, Integer flightTime, String cancellationReason, Boolean checkPreviousEvent, Boolean checkNextEvent) throws VtsException;
    EmptyLegOfferDto update(EmptyLegOfferDto emptyLegOfferDto) throws VtsException;
    Boolean checkIfMinAndMaxDepartureStillValid(EmptyLegOfferDto el, AircraftPreviousEventDto aircraftPreviousEventDto, AircraftNextEventDto aircraftNextEventDto, Integer turnaroundTime, Integer flightTime) throws VtsException;
    List<EmptyLegRoutePushNotificationDto> retrieveValidEmptyLegRoutesForPN(Date afterHours, Date beforeHours);
    EmptyLegOffer emptyLegOfferDtoToEntity(EmptyLegOfferDto emptyLegOfferDto);
    boolean hasCrewAvailableInPeriod(Integer aircraftId, Date fromDate, Date toDate);
    void saveEmptyLeg(EmptyLegOffer emptyLegOffer) throws Exception;
    List<EmptyLegOfferDto> getOverllapingEmptyLegs(Integer aircraftId, Date startDate, Date endDate);
    List<EmptyLegOffer> getOverllpingEmptyLegsOnAircraftEvents(AircraftAvailabilityDto dto);
    void changeAdvertisedToAvinodeStatus(Integer emptyLegOfferId, Boolean wasAdvertisedToAvinode);
    List<EmptyLegOfferDto> retrieveAvinodeExpiredEmptyLegs();

}
