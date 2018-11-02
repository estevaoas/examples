package com.vistajet.business.logic.observer.flightleg;

import com.vistajet.business.logic.flightleg.change.event.*;
import com.vistajet.business.logic.observer.Observer;
import com.vistajet.business.logic.observer.flightleg.crew.CrewUpdateEvent;
import com.vistajet.model.entity.bpm.PfaFlightLegApproval;
import com.vistajet.model.exception.VtsException;


public interface FlightLegUpdateObserver extends Observer {

    void handleFlightLegUpdate(FlightLegUpdateEvent updateEvent) throws VtsException;

    void handleFlightLegUpdate(PfaFlightLegApproval flightLegApproval) throws VtsException;

    void handleFlightLegUpdate(CrewUpdateEvent crewUpdateEvent) throws VtsException;

    void handleFlightLegUpdate(PassengersUpdateEvent passengersUpdateEvent) throws VtsException;

    void handleFlightLegUpdate(NumberOfPassengersChangeEvent paxNumberUpdateEvent) throws VtsException;

    void handleFlightLegUpdate(PrefUpdateEvent prefUpdateEvent) throws VtsException;

    void handleFlightLegUpdateAlter (FlightLegUpdateEvent flightLegUpdateEvent) throws VtsException;

    void handleFlightLegUpdate(FBOChangeEvent fBOChangeEvent) throws VtsException;

    void handleFlightLegUpdate(PaxCateringPrefsUpdateEvent paxCateringPrefsUpdateEvent) throws VtsException;

}
