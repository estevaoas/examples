package com.vistajet.model.entity;

import com.google.common.collect.Lists;
import com.vistajet.model.dto.EmptyLegOfferDto;
import com.vistajet.model.dto.EmptyLegRouteDto;
import com.vistajet.model.dto.TimelineEmptyLegOfferDto;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import javax.persistence.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@NamedQueries({
        @NamedQuery(name = "EmptyLegOffer.getEmptyLegOffersForPricer",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id IN (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " LEFT JOIN eo.emptyLegRoutes elr" +
                        " WHERE elr.validTo >= :time and elr.isActive = :isActive)"),


        @NamedQuery(name = "EmptyLegOffer.getFlightLegIdsByEmptyLegOffers",
                query = " SELECT DISTINCT eo.originalFerry.id"+
                        " FROM EmptyLegOffer eo" +
                        " LEFT JOIN eo.emptyLegRoutes elr" +
                        " WHERE elr.validTo >= :time and elr.isActive = :isActive and  eo.originalFerry.id IS NOT NULL "),


        @NamedQuery(name = "EmptyLegOffer.getFlightLegIdsByEmptyLegOffersWithValidTo",
                query = " SELECT DISTINCT eo.originalFerry.id"+
                        " FROM EmptyLegOffer eo" +
                        " LEFT JOIN eo.emptyLegRoutes elr" +
                        " WHERE elr.validTo <= :validTo and elr.isActive = :isActive and  eo.originalFerry.id IS NOT NULL "),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegById",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " WHERE eo.id = :id)"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegOffers",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr" +
                        " WHERE eo.originalFerry.id = :flightLegId" +
                        " AND elr.isActive = true)"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegOffersEntity",
                query = " SELECT elo FROM EmptyLegOffer elo " +
                        " JOIN FETCH elo.emptyLegRoutes elr " +
                        " WHERE elo.originalFerry.id = :flightLegId"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegBySegment",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " WHERE eo.segmentStart = :segmentStart" +
                        " AND eo.segmentEnd = :segmentEnd" +
                        " AND eo.aircraft.id = :aicraftId)"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegByMinMaxDeparture",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE elr.minDeparture = :minDeparture" +
                        " AND elr.maxDeparture = :maxDeparture" +
                        " AND eo.aircraft.id = :aicraftId)"),

        @NamedQuery(name = "EmptyLegOffer.getNotCancelledEmptyLegsInsideSegment",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " WHERE ( eo.segmentStart BETWEEN :startDate and :endDate" +
                        " OR eo.segmentEnd BETWEEN :startDate and :endDate)" +
                        " AND eo.offerStatus.id NOT IN (:excludedOfferIds)" +
                        " AND eo.aircraft.id = :aicraftId )"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegsInsideSegment",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE ( elr.minDeparture BETWEEN :startDate and :endDate" +
                        " OR elr.maxDeparture BETWEEN :startDate and :endDate)" +
                        " AND eo.aircraft.id = :aicraftId" +
                        " AND eo.offerStatus.id NOT IN (:excludedOfferIds))"),

        @NamedQuery(name = "EmptyLegOffer.getOverllapingEmptyLegs",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE ( elr.minDeparture BETWEEN :startDate and :endDate" +
                        " OR elr.maxDeparture BETWEEN :startDate and :endDate" +
                        " OR :startDate BETWEEN elr.minDeparture and elr.maxDeparture" +
                        " OR  :endDate BETWEEN elr.minDeparture and elr.maxDeparture )" +
                        " AND eo.aircraft.id = :aircraftId" +
                        " AND eo.offerStatus.id NOT IN (:excludedOfferIds))"),

        @NamedQuery(name = "EmptyLegOffer.getAllOverlapEmptyLegsValidToBeCancel",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE (elr.minDeparture BETWEEN :startDate and :endDate" +
                        " OR elr.maxDeparture BETWEEN :startDate and :endDate" +
                        " OR :startDate BETWEEN elr.minDeparture and elr.maxDeparture" +
                        " OR :endDate BETWEEN elr.minDeparture and elr.maxDeparture)" +
                        " AND eo.aircraft.id = :aircraftId" +
                        " AND eo.offerStatus.id NOT IN (:excludeOfferStatusId))"),

        @NamedQuery(name = "EmptyLegOffer.getOffersPerAircraftTypeAndTime",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id " +
                        " FROM EmptyLegOffer eo " +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE ( elr.minDeparture BETWEEN :startDate and :endDate " +
                        " OR elr.maxDeparture BETWEEN :startDate and :endDate " +
                        " OR :startDate BETWEEN elr.minDeparture and elr.maxDeparture " +
                        " OR  :endDate BETWEEN elr.minDeparture and elr.maxDeparture ) " +
                        " AND eo.aircraft.aircraftType.id = :aircraftTypeId " +
                        " AND eo.offerStatus.id  NOT IN (:excludedOfferIds))"),
        @NamedQuery(name = "EmptyLegOffer.getOffersPerAircraftType",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id " +
                        " FROM EmptyLegOffer eo " +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE ( :startDate BETWEEN elr.minDeparture  and elr.maxDeparture " +
                        " OR elr.minDeparture >= :startDate) " +
                        " AND eo.aircraft.aircraftType.id = :aircraftTypeId " +
                        " AND eo.offerStatus.id  NOT IN (:excludedOfferIds))"),
        @NamedQuery(name = "EmptyLegOffer.getOffersPerAircraftTail",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id " +
                        " FROM EmptyLegOffer eo " +
                        " INNER JOIN eo.emptyLegRoutes elr " +
                        " WHERE (:startDate BETWEEN elr.minDeparture  and elr.maxDeparture " +
                        " OR elr.minDeparture >= :startDate) " +
                        " AND eo.aircraft.id = :aircraftId " +
                        " AND eo.offerStatus.id  NOT IN (:excludedOfferIds))"),
        @NamedQuery(name = "EmptyLegOffer.getEmptyLegsInsideSegmentByStatus",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " WHERE ( eo.segmentStart BETWEEN :startDate and :endDate" +
                        " OR eo.segmentEnd BETWEEN :startDate and :endDate)" +
                        " AND eo.aircraft.id = :aicraftId" +
                        " AND eo.offerStatus.id IN (:statusId) )"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegBySegmentStart",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " WHERE eo.segmentStart = :segmentStart" +
                        " AND eo.aircraft.id = :aicraftId" +
                        " AND eo.offerStatus.id NOT IN (:excludedOfferIds))"),

        @NamedQuery(name = "EmptyLegOffer.getTimelineEmptyLegOffersByIds",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr" +
                        " WHERE  " +
                        " elr.isActive = true AND eo.id IN (:ids) )"),

        @NamedQuery(name = "EmptyLegOffer.getTimelineEmptyLegOffersDtoByStatus",
                query = " SELECT new com.vistajet.model.vo.TimelineEmptyLegOfferVo(elo.id, elo.aircraft.id, elo.segmentStart, elo.segmentEnd, elo.offerStatus.id, elo.offerStatus.name, elo.offerStatus.description, elo.cancellationReason)" +
                        " FROM EmptyLegOffer elo " +
                        " JOIN elo.emptyLegRoutes elr" +
                        " WHERE elr.isActive = true AND elo.offerStatus.id IN (:ids)"
                ),

        @NamedQuery(name = "EmptyLegOffer.getTimelineEmptyLegOffersDtoByIds",
                query = " SELECT new com.vistajet.model.vo.TimelineEmptyLegOfferVo(elo.id, elo.aircraft.id, elo.segmentStart, elo.segmentEnd, elo.offerStatus.id, elo.offerStatus.name, elo.offerStatus.description, elo.cancellationReason)" +
                        " FROM EmptyLegOffer elo " +
                        " JOIN elo.emptyLegRoutes elr" +
                        " WHERE elr.isActive = true AND elo.id IN (:ids)"
                ),
        @NamedQuery(name = "EmptyLegOffer.getTEmptyLegRoutesIdsByEmptyLegOfferIds",
                query = " SELECT DISTINCT new java.lang.Integer(elr.id)" +
                        " FROM EmptyLegOffer elo " +
                        " JOIN elo.emptyLegRoutes elr " +
                        " WHERE  elr.isActive = true AND elo.id IN (:ids)"
                ),

        @NamedQuery(name = "EmptyLegOffer.getActiveEmptyLegRoutes",
                query = " SELECT new com.vistajet.model.vo.EmptyLegRouteVo(" +
                        "   r.id," +
                        "   r.emptyLegOffer.id," +
                        "   r.validFrom," +
                        "   r.validTo," +
                        "   r.fspValidFrom," +
                        "   r.fspValidTo," +
                        "   r.avinodeValidFrom," +
                        "   r.avinodeValidTo," +
                        "   r.creator.id," +
                        "   r.creator.person.firstName," +
                        "   r.creator.person.lastName," +
                        "   r.departureAirport.id," +
                        "   r.departureAirport.icao," +
                        "   r.departureAirport.name," +
                        "   r.arrivalAirport.id," +
                        "   r.arrivalAirport.icao," +
                        "   r.arrivalAirport.name," +
                        "   r.minDeparture," +
                        "   r.maxDeparture," +
                        "   r.amountCents" +
                        " ) " +
                        " FROM EmptyLegRoute r" +
                        " WHERE r.isActive = true " +
                        " ORDER BY r.id ASC"),

        @NamedQuery(name = "EmptyLegOffer.getTimelineEmptyLegOffersByDate",
                query = " SELECT elo FROM EmptyLegOffer elo WHERE elo.id in (SELECT DISTINCT eo.id"+
                        " FROM EmptyLegOffer eo" +
                        " INNER JOIN eo.emptyLegRoutes elr" +
                        " WHERE elr.isActive = true " +
                        " AND :startTime <= elr.validFrom AND elr.validTo <= :endTime" +
                        " AND eo.offerStatus.id NOT IN (:excludedOfferIds))"),

        @NamedQuery(name = "EmptyLegOffer.getEmptyLegsByFerryId",
                query = "SELECT DISTINCT elr FROM EmptyLegOffer el " +
                        "INNER JOIN el.emptyLegRoutes elr " +
                        "INNER JOIN el.originalFerry f WHERE f.id = :flightLegId and elr.isActive = :isActive " +
                        "ORDER BY elr.id"
        ),
        @NamedQuery(name = "EmptyLegOffer.getValidEmptyLegsForPN",
                query = "SELECT DISTINCT elr FROM EmptyLegOffer el " +
                        "INNER JOIN el.emptyLegRoutes elr " +
                        "INNER JOIN el.originalFerry f WHERE f.id = :flightLegId and elr.isActive = :isActive " +
                        "ORDER BY elr.id"
        ),
        @NamedQuery(name = "EmptyLegOffer.getAvinodeExpiredEmptyLegs",
                query = "SELECT el FROM EmptyLegOffer el " +
                        "INNER JOIN el.emptyLegRoutes elr " +
                        "WHERE el.wasAdvertisedToAvinode = TRUE " +
                        "AND el.offerStatus.id = :offerStatusId " +
                        "AND elr.avinodeValidTo <= :currentTime "
        )
})

@Audited
@Table(name = "sa_empty_leg_offers")
public class EmptyLegOffer implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    @SequenceGenerator(allocationSize=1, name = "EmptyLegOffer_SEQUENCE", sequenceName = "sa_empty_leg_offer_seq")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "EmptyLegOffer_SEQUENCE")
    private Integer id;

    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    @JoinColumn(name = "sa_offer_status_id", referencedColumnName = "id")
    @ManyToOne(optional = false)
    private OfferStatus offerStatus;

    @JoinColumn(name = "vj_aircraft_id", referencedColumnName = "id")
    @ManyToOne(optional = false)
    private Aircraft aircraft;

    @Column(name = "segment_start")
    @Temporal(TemporalType.TIMESTAMP)
    private Date segmentStart;

    @Column(name = "segment_ends")
    @Temporal(TemporalType.TIMESTAMP)
    private Date segmentEnd;

    @JoinColumn(name = "op_original_ferry_id", referencedColumnName = "id", nullable = true)
    @ManyToOne(optional = true, cascade = {CascadeType.REFRESH}, fetch = FetchType.LAZY)
    private FlightLeg originalFerry;

    @JoinColumn(name = "vj_seg_arrival_airport_id", referencedColumnName = "id", nullable = true)
    @ManyToOne(optional = true, cascade = {CascadeType.REFRESH}, fetch = FetchType.LAZY)
    private Airport segArrivalAirport;

    @JoinColumn(name = "vj_seg_departure_airport_id", referencedColumnName = "id", nullable = true)
    @ManyToOne(optional = true, cascade = {CascadeType.REFRESH}, fetch = FetchType.LAZY)
    private Airport segDepartureAirport;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "emptyLegOffer", fetch = FetchType.LAZY)
    @OrderBy("id asc")
    private List<EmptyLegRoute> emptyLegRoutes;

    @Basic(fetch = FetchType.LAZY, optional = true)
    @Column (name="notes")
    private String notes;

    @Basic(optional = true)
    @Column (name="was_advertised")
    private Boolean wasAdvertised;

    @Column(name = "was_advertised_to_avinode")
    private Boolean wasAdvertisedToAvinode;

    @Column (name="cancellation_reason")
    private String cancellationReason;

    public EmptyLegOffer() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public OfferStatus getOfferStatus() {
        return offerStatus;
    }

    public void setOfferStatus(OfferStatus offerStatus) {
        this.offerStatus = offerStatus;
    }

    public Aircraft getAircraft() {
        return aircraft;
    }

    public void setAircraft(Aircraft aircraft) {
        this.aircraft = aircraft;
    }

    public FlightLeg getOriginalFerry() {
        return originalFerry;
    }

    public void setOriginalFerry(FlightLeg originalFerry) {
        this.originalFerry = originalFerry;
    }

    public List<EmptyLegRoute> getEmptyLegRoutes() {
        return emptyLegRoutes;
    }

    public void setEmptyLegRoutes(List<EmptyLegRoute> emptyLegRoutes) {
        this.emptyLegRoutes = emptyLegRoutes;
	}

    public Date getSegmentStart() {
        return segmentStart;
    }

    public void setSegmentStart(Date segmentStart) {
        this.segmentStart = segmentStart;
    }

    public Date getSegmentEnd() {
        return segmentEnd;
    }

    public void setSegmentEnd(Date segmentEnd) {
        this.segmentEnd = segmentEnd;
    }

    public Airport getSegDepartureAirport() {
        return segDepartureAirport;
    }

    public void setSegDepartureAirport(Airport segDepartureAirport) {
        this.segDepartureAirport = segDepartureAirport;
    }

    public Airport getSegArrivalAirport() {
        return segArrivalAirport;
    }

    public void setSegArrivalAirport(Airport segArrivalAirport) {
        this.segArrivalAirport = segArrivalAirport;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void addEmptyLegRoute(EmptyLegRoute route) {
        route.setEmptyLegOffer(this);
        emptyLegRoutes.add(route);
    }

    public Boolean getWasAdvertised() {
        return wasAdvertised;
    }

    public void setWasAdvertised(Boolean wasAdvertised) {
        this.wasAdvertised = wasAdvertised;
    }

    public Boolean getWasAdvertisedToAvinode() {
        return wasAdvertisedToAvinode;
    }

    public void setWasAdvertisedToAvinode(Boolean wasAdvertisedToAvinode) {
        this.wasAdvertisedToAvinode = wasAdvertisedToAvinode;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmptyLegOffer emptyLegOffer = (EmptyLegOffer) o;
        if (!aircraft.equals(emptyLegOffer.aircraft)) return false;
        if (!id.equals(emptyLegOffer.id)) return false;
        if (!offerStatus.equals(emptyLegOffer.offerStatus)) return false;
        if (!originalFerry.equals(emptyLegOffer.originalFerry)) return false;


        return true;
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + offerStatus.hashCode();
        result = 31 * result + aircraft.hashCode();
        result = 31 * result + (originalFerry != null ? originalFerry.hashCode() : 0);
        return result;
    }

    public EmptyLegOfferDto asDto(){
        EmptyLegOfferDto emptyLegOfferDto = new EmptyLegOfferDto();
        emptyLegOfferDto.setId(id);
        if(originalFerry != null) {
            emptyLegOfferDto.setOriginalFerry(originalFerry.asCoreDto());
        }
        if(segArrivalAirport != null)
            emptyLegOfferDto.setSegArrivalAirport(segArrivalAirport.asDto());
        if(segDepartureAirport != null)
        emptyLegOfferDto.setSegDepartureAirport(segDepartureAirport.asDto());
        emptyLegOfferDto.setSegmentEnd(segmentEnd);
        emptyLegOfferDto.setSegmentStart(segmentStart);
        emptyLegOfferDto.setAircraft(aircraft.asDto());
        emptyLegOfferDto.setOfferStatus(offerStatus.asDto());
        emptyLegOfferDto.setWasAdvertised(wasAdvertised);
        emptyLegOfferDto.setWasAdvertisedToAvinode(wasAdvertisedToAvinode);
        ArrayList emptyLegRoutesDto = new ArrayList();
        for (EmptyLegRoute emptyLegRoute : emptyLegRoutes){
            if(emptyLegRoute.getIsActive()) {
                EmptyLegRouteDto emptyLegRouteDto = emptyLegRoute.asDto();
                if (originalFerry == null) {
                    emptyLegRouteDto.setScheduledDepartureUtc(emptyLegOfferDto.getSegmentStart());
                    emptyLegRouteDto.setScheduledArrivalUtc(emptyLegOfferDto.getSegmentEnd());
                } else {
                    emptyLegRouteDto.setScheduledDepartureUtc(originalFerry.getScheduledDeparture());
                    emptyLegRouteDto.setScheduledArrivalUtc(originalFerry.getScheduledArrival());
                }
                emptyLegRoutesDto.add(emptyLegRouteDto);
            }
        }
        emptyLegOfferDto.setEmptyLegRoutes(emptyLegRoutesDto);

        if(this.getNotes()!=null)emptyLegOfferDto.setNotes(this.getNotes());
            return emptyLegOfferDto;
    }

    public TimelineEmptyLegOfferDto asDtoForTimeline(){
        TimelineEmptyLegOfferDto timelineEmptyLegOfferDto = new TimelineEmptyLegOfferDto();
        timelineEmptyLegOfferDto.setId(id);
        timelineEmptyLegOfferDto.setAircraftId(aircraft.getId());
        timelineEmptyLegOfferDto.setAirportId(emptyLegRoutes.get(0).getDepartureAirport().getId());
        timelineEmptyLegOfferDto.setValidFrom(emptyLegRoutes.get(0).getValidFrom());
        timelineEmptyLegOfferDto.setValidTo(emptyLegRoutes.get(0).getValidTo());
        timelineEmptyLegOfferDto.setFspValidFrom(emptyLegRoutes.get(0).getFspValidFrom());
        timelineEmptyLegOfferDto.setFspValidTo(emptyLegRoutes.get(0).getFspValidTo());
        timelineEmptyLegOfferDto.setAvinodeValidFrom(emptyLegRoutes.get(0).getAvinodeValidFrom());
        timelineEmptyLegOfferDto.setAvinodeValidTo(emptyLegRoutes.get(0).getAvinodeValidTo());
        timelineEmptyLegOfferDto.setOfferStatus(offerStatus.asDto());
        timelineEmptyLegOfferDto.setSegmentStart(segmentStart);
        timelineEmptyLegOfferDto.setSegmentEnd(segmentEnd);
        timelineEmptyLegOfferDto.setCancellationReason(cancellationReason);
        if(emptyLegRoutes.get(0).getCreator() != null && emptyLegRoutes.get(0).getCreator().getPerson() != null){
            timelineEmptyLegOfferDto.setCreatorName(emptyLegRoutes.get(0).getCreator().getPerson().getNameFirstNameFirst());
        }
        ArrayList emptyLegRoutesDto = new ArrayList();
        for (EmptyLegRoute emptyLegRoute : emptyLegRoutes){
            if(emptyLegRoute.getIsActive()) emptyLegRoutesDto.add(emptyLegRoute.asDtoForTimeline());
        }
        timelineEmptyLegOfferDto.setEmptyLegRoutes(emptyLegRoutesDto);
        return timelineEmptyLegOfferDto;
    }

    public EmptyLegOfferDto asActiveDto(){
        EmptyLegOfferDto emptyLegOfferDto = new EmptyLegOfferDto();
        emptyLegOfferDto.setId(id);
        emptyLegOfferDto.setSegmentEnd(segmentEnd);
        emptyLegOfferDto.setSegmentStart(segmentStart);
        emptyLegOfferDto.setWasAdvertised(wasAdvertised);
        emptyLegOfferDto.setWasAdvertisedToAvinode(wasAdvertisedToAvinode);
        if(originalFerry != null) {
            emptyLegOfferDto.setOriginalFerry(originalFerry.asCoreDto());
        }
        if(segArrivalAirport != null)
            emptyLegOfferDto.setSegArrivalAirport(segArrivalAirport.asLightDto());
        if(segDepartureAirport != null)
            emptyLegOfferDto.setSegDepartureAirport(segDepartureAirport.asLightDto());
        if(aircraft != null)
            emptyLegOfferDto.setAircraft(aircraft.asLightDtoWithExtIds());
        if(offerStatus != null)
            emptyLegOfferDto.setOfferStatus(offerStatus.asDto());
        ArrayList emptyLegRoutesDto = new ArrayList();
        if (emptyLegRoutes != null) {
            for (EmptyLegRoute emptyLegRoute : emptyLegRoutes) {
                if (emptyLegRoute.getIsActive() != false) {
                    EmptyLegRouteDto emptyLegRouteDto = emptyLegRoute.asDto();
                    if (originalFerry == null) {
                        emptyLegRouteDto.setScheduledDepartureUtc(emptyLegOfferDto.getSegmentStart());
                        emptyLegRouteDto.setScheduledArrivalUtc(emptyLegOfferDto.getSegmentEnd());
                    } else {
                        emptyLegRouteDto.setScheduledDepartureUtc(originalFerry.getScheduledDeparture());
                        emptyLegRouteDto.setScheduledArrivalUtc(originalFerry.getScheduledArrival());
                    }
                    emptyLegRoutesDto.add(emptyLegRouteDto);
                }
            }
        }
        emptyLegOfferDto.setEmptyLegRoutes(emptyLegRoutesDto);
        if(this.getNotes()!=null)emptyLegOfferDto.setNotes(this.getNotes());
        return emptyLegOfferDto;
    }

    public EmptyLegOfferDto asDtoWithoutRoutes(){
        EmptyLegOfferDto emptyLegOfferDto = new EmptyLegOfferDto();
        emptyLegOfferDto.setId(id);
        if(originalFerry != null) {
            emptyLegOfferDto.setOriginalFerry(originalFerry.asCoreDto());
        }
        if(segArrivalAirport != null)
            emptyLegOfferDto.setSegArrivalAirport(segArrivalAirport.asDto());
        if(segDepartureAirport != null)
            emptyLegOfferDto.setSegDepartureAirport(segDepartureAirport.asDto());
        emptyLegOfferDto.setSegmentEnd(segmentEnd);
        emptyLegOfferDto.setSegmentStart(segmentStart);
        emptyLegOfferDto.setAircraft(aircraft.asLightDto());
        emptyLegOfferDto.setOfferStatus(offerStatus.asDto());
        emptyLegOfferDto.setWasAdvertised(wasAdvertised);
        emptyLegOfferDto.setWasAdvertisedToAvinode(wasAdvertisedToAvinode);
        if(this.getNotes()!=null)emptyLegOfferDto.setNotes(this.getNotes());
        return emptyLegOfferDto;
    }

}
