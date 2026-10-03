import java.io.Serializable;
import java.time.*;
import java.util.*;

/** Domain model. Everything is Serializable so the whole database persists to disk. */
record Airport(String code, String city, String country, double lat, double lon) implements Serializable {
    boolean india() { return country.equals("India"); }
    @Override public String toString() { return city + " (" + code + ")"; }
}

record Flight(String id, String airline, String no, Airport from, Airport to, LocalDateTime dep,
              LocalDateTime arr, int mins, double fare, boolean intl) implements Serializable {}

/** One bookable itinerary for a single journey: a direct flight (1 segment) or a 1-stop connection (2 segments). */
record Option(List<Flight> segs) {
    Flight first() { return segs.get(0); }
    Flight last() { return segs.get(segs.size() - 1); }
    LocalDateTime dep() { return first().dep(); }
    LocalDateTime arr() { return last().arr(); }
    int stops() { return segs.size() - 1; }
    int mins() { return (int) Duration.between(dep(), arr()).toMinutes(); }
    /** Layover at the connecting airport in minutes (0 for direct flights). */
    int layover() { return segs.size() < 2 ? 0 : (int) Duration.between(segs.get(0).arr(), segs.get(1).dep()).toMinutes(); }
    double fare() { double t = 0; for (Flight f : segs) t += f.fare(); return t; }
    boolean intl() { for (Flight f : segs) if (f.intl()) return true; return false; }
    String via() { return stops() == 0 ? "" : segs.get(0).to().code(); }
    String airlines() { Set<String> s = new LinkedHashSet<>(); for (Flight f : segs) s.add(f.airline()); return String.join(" + ", s); }
    String numbers() { List<String> s = new ArrayList<>(); for (Flight f : segs) s.add(f.no()); return String.join(" + ", s); }
}

/** Passenger category: drives the fare factor and whether a seat is needed. */
enum PaxType {
    ADULT("Adult", "12+ years", 1.0, true),
    CHILD("Child", "2-11 years", 0.75, true),
    INFANT("Infant", "Under 2 (lap infant, no seat)", 0.10, false);

    private final String label, rule;
    private final double factor;
    private final boolean seated;

    PaxType(String label, String rule, double factor, boolean seated) {
        this.label = label; this.rule = rule; this.factor = factor; this.seated = seated;
    }
    String label() { return label; }
    String rule() { return rule; }
    double factor() { return factor; }
    boolean seated() { return seated; }
    @Override public String toString() { return label; }
    static PaxType forAge(int age) { return age < 2 ? INFANT : age < 12 ? CHILD : ADULT; }
}

/**
 * Fare families. Each has its own price, free baggage, refund tiers (48h+, 24-48h, 4-24h, under 4h),
 * change-fee share, free meal and paid-seat discount.
 */
enum FareType {
    SAVER("Saver", "Lowest price", 1.00, 15, false, 1.0, 0.0, new double[]{0.70, 0.50, 0.00, 0.00}),
    FLEX("Flex", "Balanced", 1.10, 20, false, 0.5, 0.0, new double[]{0.90, 0.75, 0.50, 0.00}),
    PREMIUM("Premium", "Most flexible", 1.25, 25, true, 0.0, 0.5, new double[]{1.00, 0.90, 0.75, 0.50});

    private final String label, tag;
    private final double priceFactor, changeFeeFactor, seatDiscount;
    private final int bagKg;
    private final boolean freeMeal;
    private final double[] refund;

    FareType(String label, String tag, double priceFactor, int bagKg, boolean freeMeal, double changeFeeFactor,
             double seatDiscount, double[] refund) {
        this.label = label; this.tag = tag; this.priceFactor = priceFactor; this.bagKg = bagKg; this.freeMeal = freeMeal;
        this.changeFeeFactor = changeFeeFactor; this.seatDiscount = seatDiscount; this.refund = refund;
    }
    String label() { return label; }
    String tag() { return tag; }
    double priceFactor() { return priceFactor; }
    int bagKg() { return bagKg; }
    boolean freeMeal() { return freeMeal; }
    double changeFeeFactor() { return changeFeeFactor; }
    double seatDiscount() { return seatDiscount; }
    double[] refundTiers() { return refund; }
    double refundPct(long hoursBefore) {
        return hoursBefore >= 48 ? refund[0] : hoursBefore >= 24 ? refund[1] : hoursBefore >= 4 ? refund[2] : refund[3];
    }
    String priceNote() {
        int p = (int) Math.round((priceFactor - 1) * 100);
        return p == 0 ? "Base fare" : "+" + p + "% on base fare";
    }
    List<String> perks() {
        List<String> l = new ArrayList<>();
        l.add(bagKg + " kg check-in + 7 kg cabin");
        l.add(freeMeal ? "Free in-flight meal" : "Meals can be added");
        l.add("Refund up to " + Math.round(refund[0] * 100) + "%");
        l.add(changeFeeFactor == 0 ? "Free changes (fare difference only)" : changeFeeFactor >= 1 ? "Full change fee" : "50% off change fee");
        l.add(seatDiscount > 0 ? "50% off paid seats" : "Paid seats at full price");
        return l;
    }
    String oneLine() {
        return bagKg + " kg bag  ·  refund up to " + Math.round(refund[0] * 100) + "%  ·  "
                + (changeFeeFactor == 0 ? "free changes" : changeFeeFactor >= 1 ? "full change fee" : "half change fee")
                + (freeMeal ? "  ·  free meal" : "");
    }
    @Override public String toString() { return label; }
}

class User implements Serializable {
    private static final long serialVersionUID = 1L;
    String name, email, phone, salt, hash;
    LocalDate joined = LocalDate.now();
    boolean admin;

    // --- rewards & referrals (added in v3; null/0 for older accounts) ---
    String refCode;            // this user's own shareable code
    String referredBy;         // email of the referrer, if any
    boolean refRewarded;       // referral bonus already paid out
    int bonusPoints;           // referral bonuses and restored points
    int pointsSpent;           // SkyPoints redeemed against fares
}

class Booking implements Serializable {
    private static final long serialVersionUID = 1L;

    String pnr, email, cls, pay, promo = "-", meal = "No meal";
    int bags;
    boolean insurance, cancelled;
    Flight flight;                       // first leg (kept for backwards compatibility)
    List<String> names, seats;           // seats = seats on the first leg
    double total, discount, refund;
    LocalDateTime bookedAt = LocalDateTime.now();

    // --- added in v2 ---
    List<Flight> legs;                   // null for old single-leg bookings; otherwise every flight segment in order
    List<List<String>> legSeats;         // seats per leg, parallel to legs
    List<PaxType> types;                 // parallel to names; null = everyone is an adult
    double seatFees, changeFees;         // paid seat surcharges / paid change fees
    Set<String> checkedLegs = new HashSet<>();     // flight ids that are web-checked-in
    List<String> history = new ArrayList<>();      // audit trail (changes, check-ins)

    // --- added in v3 ---
    String fareName;                     // FareType name; null = legacy booking (old refund policy)
    List<Integer> journey;               // journey index per leg (0 = outbound, 1 = return ...); null = each leg is its own journey
    List<Integer> bagPer, mealPer;       // per-passenger baggage / meal choice (index into Pricing.BAGS / MEALS)
    int pointsUsed;                      // SkyPoints redeemed on this booking (1 point = Rs 1)

    List<Flight> legs() { return legs == null ? List.of(flight) : legs; }
    List<String> legSeats(int i) { return legSeats == null ? seats : legSeats.get(i); }
    FareType fare() { return fareName == null ? null : FareType.valueOf(fareName); }

    List<Integer> jr() {
        if (journey != null) return journey;
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < legs().size(); i++) l.add(i);
        return l;
    }
    int journeyCount() { List<Integer> j = jr(); return j.get(j.size() - 1) + 1; }
    /** Number of connections (flight segments minus journeys). */
    int stops() { return legs().size() - journeyCount(); }
    List<Flight> journeyLegs(int j) {
        List<Flight> l = new ArrayList<>();
        for (int i = 0; i < legs().size(); i++) if (jr().get(i) == j) l.add(legs().get(i));
        return l;
    }
    boolean roundTrip() {
        if (journeyCount() != 2) return false;
        List<Flight> a = journeyLegs(0), b = journeyLegs(1);
        return a.get(0).from().equals(b.get(b.size() - 1).to()) && a.get(a.size() - 1).to().equals(b.get(0).from());
    }
    String tripType() { return journeyCount() == 1 ? "One-way" : roundTrip() ? "Round trip" : "Multi-city"; }
    /** "DEL → BOM → DEL" style route chain. */
    String chain() {
        StringBuilder s = new StringBuilder();
        List<Flight> l = legs();
        for (int i = 0; i < l.size(); i++) {
            Flight f = l.get(i);
            if (i == 0) s.append(f.from().code());
            else if (!l.get(i - 1).to().equals(f.from())) s.append("  |  ").append(f.from().code());
            s.append(" → ").append(f.to().code());
        }
        return s.toString();
    }
    List<PaxType> types() {
        if (types != null) return types;
        List<PaxType> l = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) l.add(PaxType.ADULT);
        return l;
    }
    int seatedCount() { int n = 0; for (PaxType t : types()) if (t.seated()) n++; return n; }
    boolean checkedIn(Flight f) { return checkedLegs != null && checkedLegs.contains(f.id()); }

    int bagOf(int i) { return bagPer != null && i < bagPer.size() ? bagPer.get(i) : bags; }
    String mealOf(int i) { return mealPer != null && i < mealPer.size() ? Pricing.MEALS[mealPer.get(i)] : meal; }
    String bagText() {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < names.size(); i++) s.add(Pricing.BAGS[bagOf(i)]);
        return s.size() == 1 ? s.iterator().next() : "Varies by traveller";
    }
    String mealText() {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < names.size(); i++) s.add(mealOf(i));
        return s.size() == 1 ? s.iterator().next() : "Varies by traveller";
    }

    /** Converts an old single-leg booking into the multi-leg representation (needed before editing legs). */
    void ensureLegs() {
        if (legs != null) return;
        legs = new ArrayList<>(List.of(flight));
        legSeats = new ArrayList<>();
        legSeats.add(new ArrayList<>(seats));
    }

    String status() {
        if (cancelled) return "Cancelled";
        List<Flight> l = legs();
        return l.get(l.size() - 1).dep().isBefore(LocalDateTime.now()) ? "Completed" : "Upcoming";
    }
}

/** A saved route (wishlist entry). target &gt; 0 turns it into a price alert. */
record SavedRoute(String from, String to, double target) implements Serializable {}

/** A traveller review of an airline, optionally for one specific flight number. */
class Review implements Serializable {
    private static final long serialVersionUID = 1L;
    String email, name, airline, flightNo = "", text = "";
    int stars;
    LocalDate date = LocalDate.now();
}

class Db implements Serializable {
    private static final long serialVersionUID = 1L;
    Map<String, User> users = new HashMap<>();
    List<Booking> bookings = new ArrayList<>();
    Map<String, Set<String>> seats = new HashMap<>();

    // --- admin controls (added in v2) ---
    Map<String, Double> fareOverride = new HashMap<>();   // flight id -> fare set by an admin
    Set<String> grounded = new HashSet<>();                // flight ids removed from search
    Map<String, String> flightStatus = new HashMap<>();    // flight id -> "Delayed 40 min" etc.

    // --- added in v3 (null when loading an older file; Store.seed fills them in) ---
    Map<String, List<SavedRoute>> saved = new HashMap<>(); // user email -> saved routes / price alerts
    List<Review> reviews = new ArrayList<>();
}