import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Persistence: the database is saved to ~/.skyindia/data.ser after every change. */
final class Store {
    static final Path DIR = Paths.get(System.getProperty("user.home"), ".skyindia"), FILE = DIR.resolve("data.ser");
    static Db db = seed(load());
    static Db load() {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(FILE))) { return (Db) in.readObject(); }
        catch (Exception e) { return new Db(); }
    }
    /** Fills in anything an older data file does not have, and makes sure the built-in admin account exists. */
    static Db seed(Db d) {
        if (d.users == null) d.users = new HashMap<>();
        if (d.bookings == null) d.bookings = new ArrayList<>();
        if (d.seats == null) d.seats = new HashMap<>();
        if (d.fareOverride == null) d.fareOverride = new HashMap<>();
        if (d.grounded == null) d.grounded = new HashSet<>();
        if (d.flightStatus == null) d.flightStatus = new HashMap<>();
        if (d.saved == null) d.saved = new HashMap<>();
        if (d.reviews == null) d.reviews = new ArrayList<>();
        Auth.ensureAdmin(d);
        return d;
    }
    static synchronized void save() {
        try { Files.createDirectories(DIR);
            try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(FILE))) { o.writeObject(db); }
        } catch (IOException e) { e.printStackTrace(); }
    }
}

/** Authentication: salted SHA-256 password hashes, profile editing, password change, referral sign-up. */
final class Auth {
    static final String ADMIN_EMAIL = "admin@skyindia.com", ADMIN_DEFAULT_PASSWORD = "admin123";

    static String hash(String pw, String salt) {
        try { StringBuilder s = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest((salt + pw).getBytes("UTF-8"))) s.append(String.format("%02x", b));
            return s.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void ensureAdmin(Db d) {
        if (d.users.containsKey(ADMIN_EMAIL)) return;
        User u = new User(); u.name = "SkyIndia Admin"; u.email = ADMIN_EMAIL; u.phone = "1800123759"; u.admin = true;
        u.salt = Long.toHexString(new SecureRandom().nextLong()); u.hash = hash(ADMIN_DEFAULT_PASSWORD, u.salt);
        d.users.put(ADMIN_EMAIL, u);
    }
    static User register(String name, String email, String phone, String pw) { return register(name, email, phone, pw, ""); }
    static User register(String name, String email, String phone, String pw, String referral) {
        email = email.trim().toLowerCase();
        if (name.isBlank()) throw new IllegalArgumentException("Please enter your name.");
        if (!email.matches("[\\w.+-]+@[\\w-]+\\.[\\w.]+")) throw new IllegalArgumentException("Please enter a valid email.");
        if (!phone.trim().matches("\\d{10}")) throw new IllegalArgumentException("Mobile number must be 10 digits.");
        if (pw.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters.");
        if (Store.db.users.containsKey(email)) throw new IllegalArgumentException("An account with this email already exists.");
        User referrer = null;
        if (referral != null && !referral.isBlank()) {
            referrer = Rewards.byCode(referral);
            if (referrer == null) throw new IllegalArgumentException("That referral code is not valid.");
        }
        User u = new User(); u.name = name.trim(); u.email = email; u.phone = phone.trim();
        u.salt = Long.toHexString(new SecureRandom().nextLong()); u.hash = hash(pw, u.salt);
        if (referrer != null) u.referredBy = referrer.email;
        Store.db.users.put(email, u);
        Rewards.code(u);
        Store.save(); return u;
    }
    static User login(String email, String pw) {
        User u = Store.db.users.get(email.trim().toLowerCase());
        if (u == null || !u.hash.equals(hash(pw, u.salt))) throw new IllegalArgumentException("Invalid email or password.");
        return u;
    }
    /** Edits name and mobile (the email is the account key, so it cannot change). */
    static User updateProfile(User u, String name, String phone) {
        if (name.isBlank()) throw new IllegalArgumentException("Please enter your name.");
        if (!phone.trim().matches("\\d{10}")) throw new IllegalArgumentException("Mobile number must be 10 digits.");
        u.name = name.trim(); u.phone = phone.trim(); Store.save(); return u;
    }
    static void changePassword(User u, String oldPw, String newPw) {
        if (!u.hash.equals(hash(oldPw, u.salt))) throw new IllegalArgumentException("Current password is incorrect.");
        if (newPw.length() < 6) throw new IllegalArgumentException("New password must be at least 6 characters.");
        if (newPw.equals(oldPw)) throw new IllegalArgumentException("New password must be different from the current one.");
        u.salt = Long.toHexString(new SecureRandom().nextLong()); u.hash = hash(newPw, u.salt); Store.save();
    }
}

/** Result of a cheapest-fare lookup. */
record Cheap(double fare, LocalDate date) {}

/** Airports, airlines and deterministic flight schedule generation, including 1-stop connections. */
final class Catalog {
    static final List<Airport> AIRPORTS = List.of(
        new Airport("DEL", "Delhi", "India", 28.56, 77.10), new Airport("BOM", "Mumbai", "India", 19.09, 72.87),
        new Airport("BLR", "Bengaluru", "India", 13.20, 77.71), new Airport("HYD", "Hyderabad", "India", 17.24, 78.43),
        new Airport("MAA", "Chennai", "India", 12.99, 80.17), new Airport("CCU", "Kolkata", "India", 22.65, 88.45),
        new Airport("JAI", "Jaipur", "India", 26.82, 75.81), new Airport("AMD", "Ahmedabad", "India", 23.07, 72.63),
        new Airport("GOI", "Goa", "India", 15.38, 73.83), new Airport("COK", "Kochi", "India", 10.15, 76.40),
        new Airport("DXB", "Dubai", "UAE", 25.25, 55.36), new Airport("SIN", "Singapore", "Singapore", 1.36, 103.99),
        new Airport("LHR", "London", "UK", 51.47, -0.45), new Airport("BKK", "Bangkok", "Thailand", 13.69, 100.75),
        new Airport("DOH", "Doha", "Qatar", 25.27, 51.61), new Airport("JFK", "New York", "USA", 40.64, -73.78),
        new Airport("CDG", "Paris", "France", 49.01, 2.55), new Airport("KUL", "Kuala Lumpur", "Malaysia", 2.74, 101.70));
    static final Object[][] DOM = {{"IndiGo", "6E", 1.0}, {"Air India", "AI", 1.18}, {"Akasa Air", "QP", 0.92},
        {"SpiceJet", "SG", 0.88}, {"Air India Express", "IX", 0.9}, {"IndiGo", "6E", 1.05}};
    static final Object[][] INTL = {{"Air India", "AI", 1.0}, {"Emirates", "EK", 1.25}, {"Qatar Airways", "QR", 1.2},
        {"Singapore Airlines", "SQ", 1.3}, {"IndiGo", "6E", 0.85}, {"British Airways", "BA", 1.22}};

    /** Big Indian airports. Smaller airports only have direct flights to/from these. */
    static final Set<String> METROS = Set.of("DEL", "BOM", "BLR", "HYD", "MAA", "CCU");
    static final Set<String> FOREIGN_HUBS = Set.of("DXB", "DOH", "SIN");
    static final List<String> HUBS = List.of("DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "DXB", "DOH", "SIN");

    static double km(Airport a, Airport b) {
        double p1 = Math.toRadians(a.lat()), p2 = Math.toRadians(b.lat()), dl = Math.toRadians(b.lon() - a.lon()), dp = p2 - p1;
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
    static double minFare(Airport a, Airport b) {
        boolean intl = !(a.india() && b.india());
        return Math.round((intl ? 6000 + km(a, b) * 5.2 : 1800 + km(a, b) * 4.4) * 0.85 / 10.0) * 10.0;
    }
    static Airport byCode(String c) { return AIRPORTS.stream().filter(a -> a.code().equals(c)).findFirst().orElseThrow(); }
    static String dur(long minutes) { return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m"; }

    /** Is there a non-stop flight between these two airports? */
    static boolean direct(Airport a, Airport b) {
        if (a.india() && b.india()) return METROS.contains(a.code()) || METROS.contains(b.code());
        if (a.india() || b.india()) return METROS.contains((a.india() ? a : b).code());
        return FOREIGN_HUBS.contains(a.code()) || FOREIGN_HUBS.contains(b.code());
    }

    /** Raw flights for one pair and date. Admin overrides (fare changes, grounded flights) are applied here. */
    static List<Flight> search(Airport a, Airport b, LocalDate d) {
        boolean intl = !(a.india() && b.india()); double km = km(a, b);
        Object[][] air = intl ? INTL : DOM; Random r = new Random((a.code() + b.code() + d).hashCode());
        long days = Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), d));
        double dyn = days > 30 ? 0.85 : days > 14 ? 0.95 : days > 6 ? 1.05 : days > 1 ? 1.2 : 1.45;   // dynamic pricing
        List<Flight> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Object[] al = air[i]; LocalDateTime dep = d.atTime((intl ? i * 4 : 5 + i * 3) + r.nextInt(2), 5 * r.nextInt(12));
            int mins = (int) (km / (intl ? 820 : 760) * 60) + (intl ? 70 : 45) + r.nextInt(20);
            double base = intl ? 6000 + km * 5.2 : 1800 + km * 4.4;
            double fare = Math.round(base * (double) al[2] * dyn * (0.85 + r.nextDouble() * 0.4) / 10.0) * 10.0;
            String no = al[1] + " " + (100 + r.nextInt(900));
            String id = no + "-" + d + "-" + a.code() + b.code() + i;
            if (Store.db.grounded.contains(id)) continue;
            Double override = Store.db.fareOverride.get(id);
            out.add(new Flight(id, (String) al[0], no, a, b, dep, dep.plusMinutes(mins), mins, override != null ? override : fare, intl));
        }
        return out;
    }

    /**
     * Everything bookable for one journey: direct flights (where the route has them) plus 1-stop connections via a hub.
     * Routes with no direct flight still return connections, so every pair of airports gets results.
     */
    static List<Option> options(Airport a, Airport b, LocalDate d) {
        List<Option> out = new ArrayList<>();
        boolean hasDirect = direct(a, b);
        if (hasDirect) for (Flight f : search(a, b, d)) out.add(new Option(List.of(f)));

        double straight = km(a, b);
        List<Option> con = new ArrayList<>();
        for (String h : HUBS) {
            Airport hub = byCode(h);
            if (hub.equals(a) || hub.equals(b) || !direct(a, hub) || !direct(hub, b)) continue;
            if (km(a, hub) + km(hub, b) > straight * 1.5 + 200) continue;       // no silly detours
            List<Flight> second = new ArrayList<>(search(hub, b, d));
            second.addAll(search(hub, b, d.plusDays(1)));
            second.sort(Comparator.comparing(Flight::dep));
            List<Option> mine = new ArrayList<>();
            for (Flight f1 : search(a, hub, d)) {
                for (Flight f2 : second) {
                    long lay = ChronoUnit.MINUTES.between(f1.arr(), f2.dep());
                    long min = f1.intl() || f2.intl() ? 90 : 60;
                    if (lay >= min && lay <= 900) { mine.add(new Option(List.of(f1, f2))); break; }
                }
            }
            mine.sort(Comparator.comparingInt(Option::mins));
            con.addAll(mine.subList(0, Math.min(2, mine.size())));
        }
        con.sort(Comparator.comparingInt(Option::mins));
        out.addAll(con.subList(0, Math.min(hasDirect ? 4 : 8, con.size())));
        return out;
    }

    /** Cheapest economy fare over the next n days (tomorrow onwards), or null when nothing is bookable. */
    static Cheap cheapest(Airport a, Airport b, int n) {
        Cheap best = null;
        for (int i = 1; i <= n; i++) {
            LocalDate d = LocalDate.now().plusDays(i);
            for (Option o : options(a, b, d)) if (best == null || o.fare() < best.fare()) best = new Cheap(o.fare(), d);
        }
        return best;
    }

    /** Cheapest fare for every bookable day of a month (tomorrow onwards) - feeds the fare calendar. */
    static Map<LocalDate, Double> fareCalendar(Airport a, Airport b, YearMonth ym) {
        Map<LocalDate, Double> m = new TreeMap<>();
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate d = ym.atDay(day);
            if (!d.isAfter(LocalDate.now())) continue;
            options(a, b, d).stream().mapToDouble(Option::fare).min().ifPresent(v -> m.put(d, v));
        }
        return m;
    }
}

/** Seat categories and surcharges. Seat ids look like "12A" (row + letter). */
final class Seats {
    static final Set<Integer> LEGROOM_ROWS = Set.of(1, 2, 3), EXIT_ROWS = Set.of(8, 9);

    static int row(String seat) { return Integer.parseInt(seat.substring(0, seat.length() - 1)); }
    /** Window (A/F), Aisle (C/D) or Middle (B/E). */
    static String position(String seat) {
        char c = seat.charAt(seat.length() - 1);
        return c == 'A' || c == 'F' ? "Window" : c == 'C' || c == 'D' ? "Aisle" : "Middle";
    }
    static String kind(String seat) {
        int r = row(seat);
        return LEGROOM_ROWS.contains(r) ? "Extra legroom" : EXIT_ROWS.contains(r) ? "Exit row" : "Standard";
    }
    /** Surcharge in INR for one seat on one flight (Business seats are free to choose). */
    static double fee(Flight f, String seat, String cls) {
        if (cls.equals("Business")) return 0;
        String k = kind(seat);
        if (k.equals("Extra legroom")) return f.intl() ? 2000 : 800;
        if (k.equals("Exit row")) return f.intl() ? 1500 : 600;
        return 0;
    }
    static String label(String seat) { String k = kind(seat); return seat + " · " + position(seat) + (k.equals("Standard") ? "" : " · " + k); }
}

record Quote(double base, double tax, double fees, double addons, double discount, double total, String promoMsg, boolean promoOk,
             double seatFees, double memberDiscount, int pointsUsed) {
    /** Old-style quote without seat fees, member discount or points. */
    Quote(double base, double tax, double fees, double addons, double discount, double total, String promoMsg, boolean promoOk) {
        this(base, tax, fees, addons, discount, total, promoMsg, promoOk, 0, 0, 0);
    }
}

/** SkyMiles loyalty tiers, driven by lifetime points: Silver 0+, Gold 200+, Platinum 600+. */
final class Loyalty {
    record Tier(String name, int points, double discount, int nextAt, String nextName) {}

    static Tier of(String email) {
        int pts = Rewards.lifetime(email);
        if (pts >= 600) return new Tier("Platinum", pts, 0.05, 600, "");
        if (pts >= 200) return new Tier("Gold", pts, 0.03, 600, "Platinum");
        return new Tier("Silver", pts, 0.0, 200, "Gold");
    }
}

/**
 * SkyPoints and refer-and-earn. 1 point per Rs 100 paid; 1 point is worth Rs 1 against a fare (up to 30% of the amount due).
 * Referral: the new member gets 150 points and the referrer 250 points when the new member completes a first booking.
 */
final class Rewards {
    static final int REFERRER_BONUS = 250, REFEREE_BONUS = 150;
    static final double MAX_SHARE = 0.30;

    static int earned(String email) {
        double s = 0;
        for (Booking b : Bookings.of(email)) if (!b.cancelled) s += b.total;
        return (int) (s / 100);
    }
    static int lifetime(String email) {
        User u = Store.db.users.get(email);
        return earned(email) + (u == null ? 0 : u.bonusPoints);
    }
    static int balance(String email) {
        User u = Store.db.users.get(email);
        return Math.max(0, lifetime(email) - (u == null ? 0 : u.pointsSpent));
    }
    /** Most points that can be redeemed against an amount still to pay. */
    static int maxRedeem(String email, double payable) {
        return (int) Math.max(0, Math.min(balance(email), Math.floor(payable * MAX_SHARE)));
    }

    /** The user's own referral code (created on first use). */
    static String code(User u) {
        if (u.refCode == null) {
            String c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; Random r = new SecureRandom(); String code;
            do {
                StringBuilder s = new StringBuilder("SKY");
                for (int i = 0; i < 5; i++) s.append(c.charAt(r.nextInt(c.length())));
                code = s.toString();
            } while (byCode(code) != null);
            u.refCode = code; Store.save();
        }
        return u.refCode;
    }
    static User byCode(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase();
        for (User u : Store.db.users.values()) if (c.equals(u.refCode)) return u;
        return null;
    }
    /** Lets an existing member enter a friend's code, as long as they have not booked yet. */
    static User applyReferral(User u, String code) {
        if (u.referredBy != null) throw new IllegalArgumentException("You have already used a referral code.");
        User r = byCode(code);
        if (r == null) throw new IllegalArgumentException("That referral code is not valid.");
        if (r.email.equals(u.email)) throw new IllegalArgumentException("You can't use your own code.");
        for (Booking b : Bookings.of(u.email)) if (!b.cancelled) throw new IllegalArgumentException("Referral codes can only be added before your first booking.");
        u.referredBy = r.email; Store.save(); return r;
    }
    /** Called after a member's first completed booking. */
    static void onFirstBooking(User u) {
        if (u.referredBy == null || u.refRewarded) return;
        u.refRewarded = true; u.bonusPoints += REFEREE_BONUS;
        User r = Store.db.users.get(u.referredBy);
        if (r != null) r.bonusPoints += REFERRER_BONUS;
    }
    static int referrals(User u) {
        int n = 0; for (User x : Store.db.users.values()) if (u.email.equals(x.referredBy)) n++; return n;
    }
    static int rewarded(User u) {
        int n = 0; for (User x : Store.db.users.values()) if (u.email.equals(x.referredBy) && x.refRewarded) n++; return n;
    }
}

/** Ratings and reviews per airline or per flight number. */
final class Reviews {
    static List<String> airlines() {
        TreeSet<String> s = new TreeSet<>();
        for (Object[] a : Catalog.DOM) s.add((String) a[0]);
        for (Object[] a : Catalog.INTL) s.add((String) a[0]);
        return new ArrayList<>(s);
    }
    static List<Review> all() {
        List<Review> l = new ArrayList<>(Store.db.reviews);
        l.sort(Comparator.comparing((Review r) -> r.date).reversed());
        return l;
    }
    static List<Review> forAirline(String airline) {
        List<Review> l = new ArrayList<>();
        for (Review r : all()) if (r.airline.equals(airline)) l.add(r);
        return l;
    }
    static int count(String airline) { return forAirline(airline).size(); }
    static double avg(String airline) {
        List<Review> l = forAirline(airline); if (l.isEmpty()) return 0;
        double s = 0; for (Review r : l) s += r.stars; return s / l.size();
    }
    static Review add(User u, String airline, String flightNo, int stars, String text) {
        if (stars < 1 || stars > 5) throw new IllegalArgumentException("Please choose a star rating.");
        text = text == null ? "" : text.trim();
        if (text.length() > 300) throw new IllegalArgumentException("Please keep the review under 300 characters.");
        String fn = flightNo == null ? "" : flightNo.trim().toUpperCase();
        Review r = null;
        for (Review x : Store.db.reviews) if (x.email.equals(u.email) && x.airline.equals(airline) && x.flightNo.equals(fn)) r = x;
        if (r == null) { r = new Review(); Store.db.reviews.add(r); }
        r.email = u.email; r.name = u.name; r.airline = airline; r.flightNo = fn; r.stars = stars; r.text = text; r.date = LocalDate.now();
        Store.save(); return r;
    }
    /** True when the reviewer has actually flown with this airline. */
    static boolean verified(String email, String airline) {
        for (Booking b : Bookings.of(email)) if (!b.cancelled)
            for (Flight f : b.legs()) if (f.airline().equals(airline) && f.dep().isBefore(LocalDateTime.now())) return true;
        return false;
    }
}

/** Wishlist of saved routes. A target price turns a saved route into a price alert. */
final class SavedRoutes {
    static List<SavedRoute> of(String email) { return Store.db.saved.computeIfAbsent(email, k -> new ArrayList<>()); }
    static SavedRoute find(String email, Airport a, Airport b) {
        for (SavedRoute r : of(email)) if (r.from().equals(a.code()) && r.to().equals(b.code())) return r;
        return null;
    }
    static void save(String email, Airport a, Airport b, double target) {
        of(email).removeIf(r -> r.from().equals(a.code()) && r.to().equals(b.code()));
        of(email).add(new SavedRoute(a.code(), b.code(), Math.max(0, target)));
        Store.save();
    }
    static void remove(String email, SavedRoute r) { of(email).remove(r); Store.save(); }

    /** Alert notifications for every saved route whose cheapest fare in the next 30 days is at or below the target. */
    static List<String[]> triggered(String email) {
        List<String[]> l = new ArrayList<>();
        for (SavedRoute r : of(email)) {
            if (r.target() <= 0) continue;
            Airport a = Catalog.byCode(r.from()), b = Catalog.byCode(r.to());
            Cheap c = Catalog.cheapest(a, b, 30);
            if (c != null && c.fare() <= r.target())
                l.add(new String[]{"Price alert", a.code() + " → " + b.code() + " from " + Pricing.inr(c.fare()) + " on "
                        + c.date().format(DateTimeFormatter.ofPattern("dd MMM")) + " (target " + Pricing.inr(r.target()) + ")"});
        }
        return l;
    }
}

/** Pricing engine: class multipliers, fare types, passenger types, seat fees, per-passenger extras, taxes, promos, member discount, points. */
final class Pricing {
    static final Map<String, Double> CLASS_X = new LinkedHashMap<>();
    static { CLASS_X.put("Economy", 1.0); CLASS_X.put("Premium Economy", 1.5); CLASS_X.put("Business", 2.8); }
    static final String[] BAGS = {"No extra baggage", "+15 kg", "+25 kg"}, MEALS = {"No meal", "Veg meal", "Non-veg meal"};
    static final double[] BAG_DOM = {0, 1500, 2800}, BAG_INTL = {0, 3500, 6000};

    static String inr(double v) {
        String s = Long.toString(Math.round(v)); if (s.length() <= 3) return "₹" + s;
        String last = s.substring(s.length() - 3), rest = s.substring(0, s.length() - 3); StringBuilder sb = new StringBuilder();
        while (rest.length() > 2) { sb.insert(0, "," + rest.substring(rest.length() - 2)); rest = rest.substring(0, rest.length() - 2); }
        return "₹" + rest + sb + "," + last;
    }

    static double seatFee(Flight f, String seat, String cls, FareType ft) {
        double fee = Seats.fee(f, seat, cls);
        return ft == null ? fee : fee * (1 - ft.seatDiscount());
    }
    private static int at(int[] a, int i) { return a != null && i < a.length ? a[i] : 0; }
    static int[] fill(int n, int v) { int[] a = new int[n]; Arrays.fill(a, v); return a; }

    /** Original one-way, all-adult quote (unchanged behaviour). */
    static Quote quote(Flight f, String cls, int pax, int bag, int meal, boolean ins, String promo, String email) {
        return quoteTrip(List.of(f), cls, Collections.nCopies(pax, PaxType.ADULT), null, bag, meal, ins, promo, email, false);
    }

    /** Older entry point: one baggage and meal choice for everyone, no fare type, no points. */
    static Quote quoteTrip(List<Flight> legs, String cls, List<PaxType> types, List<List<String>> seats, int bag, int meal,
                           boolean ins, String promo, String email, boolean member) {
        return quoteTrip(legs, cls, types, seats, fill(types.size(), bag), fill(types.size(), meal), ins, promo, email, member, null, false);
    }

    /**
     * Full quote for one or more flight segments, mixed passenger types and per-passenger extras.
     * bags/meals: one entry per passenger (index into BAGS / MEALS), applied on every segment.
     * seats: one list per segment (may be null/shorter while the user is still choosing).
     * ft: fare type (null = legacy base fare). redeem: spend as many SkyPoints as allowed.
     */
    static Quote quoteTrip(List<Flight> legs, String cls, List<PaxType> types, List<List<String>> seats, int[] bags, int[] meals,
                           boolean ins, String promo, String email, boolean member, FareType ft, boolean redeem) {
        double mult = CLASS_X.get(cls), ftf = ft == null ? 1.0 : ft.priceFactor(), factor = 0; int seated = 0;
        for (PaxType t : types) { factor += t.factor(); if (t.seated()) seated++; }
        boolean freeMeal = ft != null && ft.freeMeal();
        double base = 0, tax = 0, fees = 0, addons = 0, seatFees = 0; boolean anyIntl = false;
        for (int i = 0; i < legs.size(); i++) {
            Flight f = legs.get(i); anyIntl |= f.intl();
            double legBase = f.fare() * mult * ftf * factor;
            base += legBase;
            tax += f.intl() ? 0 : legBase * (cls.equals("Business") ? 0.12 : 0.05);
            fees += (f.intl() ? 1500.0 : 350.0) * seated;
            double[] bt = f.intl() ? BAG_INTL : BAG_DOM;
            for (int j = 0; j < types.size(); j++) {
                if (!types.get(j).seated()) continue;
                addons += bt[at(bags, j)];
                if (!freeMeal && at(meals, j) > 0) addons += f.intl() ? 650 : 450;
            }
            if (seats != null && i < seats.size()) for (String s : seats.get(i)) seatFees += seatFee(f, s, cls, ft);
        }
        if (ins) addons += 199.0 * types.size();
        double pre = base + tax + fees + addons + seatFees, disc = 0; String msg = ""; boolean ok = false;
        String p = promo == null ? "" : promo.trim().toUpperCase();
        boolean first = Bookings.of(email).isEmpty();
        switch (p) {
            case "" -> {}
            case "SKY10" -> { disc = Math.min(1500, pre * 0.10); ok = true; msg = "SKY10 applied: 10% off (max ₹1,500)"; }
            case "WELCOME500" -> { if (first) { disc = 500; ok = true; msg = "WELCOME500 applied: ₹500 off"; } else msg = "WELCOME500 is only valid on your first booking"; }
            case "INTL2000" -> { if (anyIntl && pre >= 30000) { disc = 2000; ok = true; msg = "INTL2000 applied: ₹2,000 off"; } else msg = "INTL2000 needs an international flight with total of ₹30,000+"; }
            default -> msg = "Invalid promo code";
        }
        double memberDisc = member ? Math.round(pre * Loyalty.of(email).discount()) : 0;
        double payable = pre - disc - memberDisc;
        int pts = redeem ? Rewards.maxRedeem(email, payable) : 0;
        return new Quote(base, tax, fees, addons, disc, payable - pts, msg, ok, seatFees, memberDisc, pts);
    }
}

/** Result of asking "what would changing this booking cost?" payNow &gt; 0 means pay, &lt; 0 means you receive money back. */
record ModQuote(double changeFee, double fareDiff, double seatDiff, double payNow, String error) {
    boolean ok() { return error == null; }
    double refund() { return Math.max(0, -payNow); }
}

/** Booking, cancellation, modification, check-in, history and ticket services. */
final class Bookings {
    static final int ROWS = 16;
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy 'at' HH:mm");

    static Set<String> taken(Flight f) {
        return Store.db.seats.computeIfAbsent(f.id(), k -> { Set<String> s = new HashSet<>(); Random r = new Random(k.hashCode());
            for (int i = 1; i <= ROWS; i++) for (char c : "ABCDEF".toCharArray()) if (r.nextInt(100) < 30) s.add(i + "" + c); return s; });
    }
    static List<Booking> of(String email) {
        List<Booking> l = new ArrayList<>();
        for (Booking b : Store.db.bookings) if (b.email.equals(email)) l.add(b);
        l.sort(Comparator.comparing((Booking b) -> b.bookedAt).reversed()); return l;
    }
    static String pnr() {
        String c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; Random r = new SecureRandom(); StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) s.append(c.charAt(r.nextInt(c.length())));
        return s.toString();
    }
    /** Simulated gate for a flight (same formula the status tracker uses). */
    static String gate(Flight f) {
        int h = Math.abs(f.id().hashCode() % 100000);
        return (char) ('A' + h % 6) + String.valueOf(1 + h / 6 % 24);
    }

    /** Original one-way, all-adult booking (unchanged behaviour, no member discount). */
    static synchronized Booking book(User u, Flight f, String cls, List<String> names, List<String> seats, int bag, int meal, boolean ins, String promo, String pay) {
        List<List<String>> per = new ArrayList<>(); per.add(new ArrayList<>(seats));
        int n = names.size();
        return doBook(u, List.of(f), null, cls, names, Collections.nCopies(n, PaxType.ADULT), per,
                Pricing.fill(n, bag), Pricing.fill(n, meal), ins, promo, pay, false, null, false);
    }

    /** Older multi-leg path: one baggage and meal choice for everyone. */
    static synchronized Booking bookTrip(User u, List<Flight> legs, String cls, List<String> names, List<PaxType> types,
                                         List<List<String>> seatsPerLeg, int bag, int meal, boolean ins, String promo, String pay) {
        return doBook(u, legs, null, cls, names, types, seatsPerLeg, Pricing.fill(names.size(), bag), Pricing.fill(names.size(), meal),
                ins, promo, pay, true, null, false);
    }

    /**
     * Current booking path: round trip / multi-city / connections, per-passenger extras, fare type, member discount and points.
     * journey: journey index per flight segment (0 = outbound, 1 = return ...), or null to treat each segment as its own journey.
     */
    static synchronized Booking bookJourneys(User u, List<Flight> legs, List<Integer> journey, String cls, List<String> names,
                                             List<PaxType> types, List<List<String>> seatsPerLeg, int[] bags, int[] meals,
                                             boolean ins, String promo, String pay, FareType ft, boolean redeem) {
        return doBook(u, legs, journey, cls, names, types, seatsPerLeg, bags, meals, ins, promo, pay, true, ft, redeem);
    }

    private static Booking doBook(User u, List<Flight> legs, List<Integer> journey, String cls, List<String> names, List<PaxType> types,
                                  List<List<String>> seatsPerLeg, int[] bags, int[] meals, boolean ins, String promo, String pay,
                                  boolean member, FareType ft, boolean redeem) {
        if (legs.isEmpty()) throw new IllegalArgumentException("Select at least one flight.");
        if (names.size() != types.size()) throw new IllegalArgumentException("Passenger details are incomplete.");
        int seated = 0, adults = 0, infants = 0;
        for (PaxType t : types) { if (t.seated()) seated++; if (t == PaxType.ADULT) adults++; if (t == PaxType.INFANT) infants++; }
        if (seated == 0) throw new IllegalArgumentException("At least one adult or child must travel.");
        if (infants > adults) throw new IllegalArgumentException("Each infant must travel with an adult.");
        if (seatsPerLeg.size() != legs.size()) throw new IllegalArgumentException("Choose seats for every flight.");
        for (int i = 0; i < legs.size(); i++) {
            List<String> s = seatsPerLeg.get(i); Set<String> t = taken(legs.get(i));
            if (s.size() != seated || new HashSet<>(s).size() != seated) throw new IllegalStateException("Please select " + seated + " seat(s) for each flight.");
            for (String x : s) if (t.contains(x)) throw new IllegalStateException("Seat " + x + " was just taken. Please choose again.");
        }
        boolean firstEver = true;
        for (Booking x : of(u.email)) if (!x.cancelled) firstEver = false;
        Quote q = Pricing.quoteTrip(legs, cls, types, seatsPerLeg, bags, meals, ins, promo, u.email, member, ft, redeem);
        Booking b = new Booking(); b.pnr = pnr(); b.email = u.email; b.cls = cls; b.names = names;
        b.legs = new ArrayList<>(legs); b.flight = legs.get(0);
        b.journey = journey == null ? null : new ArrayList<>(journey);
        b.legSeats = new ArrayList<>(); for (List<String> s : seatsPerLeg) b.legSeats.add(new ArrayList<>(s));
        b.seats = new ArrayList<>(b.legSeats.get(0)); b.types = new ArrayList<>(types);
        b.bagPer = new ArrayList<>(); b.mealPer = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) { b.bagPer.add(i < bags.length ? bags[i] : 0); b.mealPer.add(i < meals.length ? meals[i] : 0); }
        b.bags = b.bagPer.get(0); b.meal = Pricing.MEALS[b.mealPer.get(0)];
        b.insurance = ins; b.pay = pay; b.total = q.total();
        b.discount = q.discount() + q.memberDiscount() + q.pointsUsed(); b.seatFees = q.seatFees();
        b.fareName = ft == null ? null : ft.name();
        b.pointsUsed = q.pointsUsed(); u.pointsSpent += q.pointsUsed();
        if (q.promoOk()) b.promo = promo.trim().toUpperCase();
        for (int i = 0; i < legs.size(); i++) taken(legs.get(i)).addAll(b.legSeats.get(i));
        b.history.add(LocalDateTime.now().format(DT) + " - Booked (" + b.tripType() + (ft == null ? "" : ", " + ft.label() + " fare") + ")");
        Store.db.bookings.add(b);
        if (firstEver) Rewards.onFirstBooking(u);
        Store.save(); return b;
    }

    /** Refund share for cancelling now: depends on the fare type (old bookings use the original policy). */
    static double refundPct(Booking b) {
        long h = ChronoUnit.HOURS.between(LocalDateTime.now(), b.legs().get(0).dep());
        if (b.fare() != null) return b.fare().refundPct(h);
        return h >= 48 ? 0.90 : h >= 24 ? 0.75 : h >= 4 ? 0.50 : 0;
    }
    private static void release(Booking b) {
        List<Flight> legs = b.legs();
        for (int i = 0; i < legs.size(); i++) { Set<String> t = Store.db.seats.get(legs.get(i).id()); if (t != null) t.removeAll(b.legSeats(i)); }
    }
    static void cancel(Booking b) { cancel(b, refundPct(b)); }
    static void cancel(Booking b, double refundFraction) {
        b.refund = b.total * refundFraction; b.cancelled = true; release(b);
        User u = Store.db.users.get(b.email);
        if (u != null && b.pointsUsed > 0) u.bonusPoints += (int) Math.round(b.pointsUsed * refundFraction);   // give back redeemed points
        b.history.add(LocalDateTime.now().format(DT) + " - Cancelled, refund " + Pricing.inr(b.refund)); Store.save();
    }

    // ---------------- web check-in ----------------
    /** The next leg that has not departed yet (null when all legs are in the past). */
    static Flight nextLeg(Booking b) {
        for (Flight f : b.legs()) if (f.dep().isAfter(LocalDateTime.now())) return f;
        return null;
    }
    /** Opens 48 hours before departure and closes 1 hour before. */
    static boolean checkinOpen(Booking b) {
        if (b.cancelled) return false;
        Flight f = nextLeg(b); if (f == null || b.checkedIn(f)) return false;
        long m = ChronoUnit.MINUTES.between(LocalDateTime.now(), f.dep());
        return m <= 48 * 60 && m >= 60;
    }
    static synchronized void checkIn(Booking b) {
        if (!checkinOpen(b)) throw new IllegalStateException("Web check-in opens 48 hours before departure and closes 1 hour before.");
        Flight f = nextLeg(b);
        if (b.checkedLegs == null) b.checkedLegs = new HashSet<>();
        b.checkedLegs.add(f.id());
        b.history.add(LocalDateTime.now().format(DT) + " - Checked in for " + f.no()); Store.save();
    }

    // ---------------- modify booking ----------------
    private static ModQuote err(String m) { return new ModQuote(0, 0, 0, 0, m); }

    /**
     * Cost preview for moving one leg to another flight (same route) and/or other seats. Nothing is changed.
     * The result is the change fee plus fare and seat differences; a negative total means money comes back.
     */
    static ModQuote modifyQuote(Booking b, int leg, Flight nf, List<String> newSeats) {
        if (b.cancelled) return err("This booking is cancelled.");
        List<Flight> legs = b.legs();
        if (leg < 0 || leg >= legs.size()) return err("Invalid flight.");
        Flight old = legs.get(leg); LocalDateTime now = LocalDateTime.now();
        if (ChronoUnit.HOURS.between(now, old.dep()) < 4) return err("Changes close 4 hours before departure.");
        if (!nf.from().equals(old.from()) || !nf.to().equals(old.to())) return err("The new flight must be on the same route.");
        if (ChronoUnit.HOURS.between(now, nf.dep()) < 4) return err("The new flight must depart at least 4 hours from now.");
        if (leg > 0 && !nf.dep().isAfter(legs.get(leg - 1).arr())) return err("The new flight must depart after the previous flight lands.");
        if (leg < legs.size() - 1 && !nf.arr().isBefore(legs.get(leg + 1).dep())) return err("The new flight must land before the next flight departs.");
        int seated = b.seatedCount();
        if (newSeats.size() != seated || new HashSet<>(newSeats).size() != seated) return err("Select exactly " + seated + " seat(s).");
        Set<String> t = taken(nf); List<String> cur = b.legSeats(leg); boolean same = nf.id().equals(old.id());
        for (String s : newSeats) if (t.contains(s) && !(same && cur.contains(s))) return err("Seat " + s + " is not available.");
        boolean flightChanged = !same, seatsChanged = !new HashSet<>(newSeats).equals(new HashSet<>(cur));
        if (!flightChanged && !seatsChanged) return err("Nothing to change.");
        FareType ft = b.fare();
        double mult = Pricing.CLASS_X.get(b.cls), factor = 0, ftf = ft == null ? 1.0 : ft.priceFactor(), cf = ft == null ? 1.0 : ft.changeFeeFactor();
        for (PaxType p : b.types()) factor += p.factor();
        double changeFee = flightChanged ? (old.intl() ? 2500 : 750) * seated * cf : 0;
        double fareDiff = flightChanged ? (nf.fare() - old.fare()) * mult * factor * ftf : 0;
        double seatDiff = 0;
        for (String s : newSeats) seatDiff += Pricing.seatFee(nf, s, b.cls, ft);
        for (String s : cur) seatDiff -= Pricing.seatFee(old, s, b.cls, ft);
        double net = changeFee + fareDiff + seatDiff;        // negative = the traveller receives the difference
        return new ModQuote(changeFee, fareDiff, seatDiff, net, null);
    }
    /** Applies the change shown by modifyQuote and returns what was charged (or refunded, if negative). */
    static synchronized ModQuote modify(Booking b, int leg, Flight nf, List<String> newSeats) {
        ModQuote q = modifyQuote(b, leg, nf, newSeats);
        if (!q.ok()) throw new IllegalStateException(q.error());
        b.ensureLegs(); Flight old = b.legs.get(leg);
        Set<String> ot = Store.db.seats.get(old.id()); if (ot != null) ot.removeAll(b.legSeats.get(leg));
        taken(nf).addAll(newSeats);
        b.legs.set(leg, nf); b.legSeats.set(leg, new ArrayList<>(newSeats));
        if (leg == 0) { b.flight = nf; b.seats = new ArrayList<>(newSeats); }
        if (b.checkedLegs != null) b.checkedLegs.remove(old.id());
        b.total += q.payNow(); b.changeFees += q.changeFee(); b.seatFees += q.seatDiff();
        b.history.add(LocalDateTime.now().format(DT) + " - Changed " + old.no() + " to " + nf.no() + " (" + nf.dep().format(DT) + "), "
                + (q.payNow() >= 0 ? "paid " + Pricing.inr(q.payNow()) : "refunded " + Pricing.inr(q.refund())));
        Store.save(); return q;
    }

    /** Adds two completed sample trips so a new account's history isn't empty. */
    static void seedHistory(User u) {
        Object[][] trips = {{"DEL", "BOM", 21}, {"BLR", "DXB", 64}};
        for (Object[] t : trips) {
            Flight f = Catalog.search(Catalog.byCode((String) t[0]), Catalog.byCode((String) t[1]), LocalDate.now().minusDays((int) t[2])).get(1);
            Booking b = new Booking(); b.pnr = "DEMO" + t[0].toString().charAt(0) + t[1].toString().charAt(0); b.email = u.email; b.flight = f; b.cls = "Economy";
            b.names = List.of(u.name); b.seats = List.of("7C"); b.pay = "UPI"; b.total = Pricing.quote(f, "Economy", 1, 0, 0, false, "", "x@x").total();
            b.bookedAt = f.dep().minusDays(14); Store.db.bookings.add(b);
        }
        Store.save();
    }

    static String ticket(Booking b) {
        Flight f = b.flight; String l = "=".repeat(52);
        FareType ft = b.fare();
        List<String> t = new ArrayList<>(List.of(l, "  SKYINDIA E-TICKET   [" + b.status().toUpperCase() + "]", l,
            "  PNR         : " + b.pnr, "  Trip type   : " + b.tripType() + (b.stops() > 0 ? " (" + b.stops() + " connection" + (b.stops() > 1 ? "s" : "") + ")" : ""),
            "  Fare type   : " + (ft == null ? "Standard" : ft.label())));
        List<Flight> legs = b.legs();
        for (int i = 0; i < legs.size(); i++) {
            Flight x = legs.get(i);
            t.add("  " + (legs.size() > 1 ? "Flight " + (i + 1) + "    : " : "Flight      : ") + x.airline() + " " + x.no() + (x.intl() ? "  (International)" : ""));
            t.add("  Route       : " + x.from().city() + " (" + x.from().code() + ") -> " + x.to().city() + " (" + x.to().code() + ")");
            t.add("  Departure   : " + x.dep().format(DT)); t.add("  Arrival     : " + x.arr().format(DT));
            t.add("  Seats       : " + String.join(", ", b.legSeats(i)) + (b.checkedIn(x) ? "   [CHECKED IN]" : ""));
        }
        t.add("  Class       : " + b.cls);
        t.add("  Passengers  :");
        for (int i = 0; i < b.names.size(); i++)
            t.add("    " + (i + 1) + ". " + b.names.get(i) + "  -  " + Pricing.BAGS[b.bagOf(i)] + ", " + b.mealOf(i));
        t.addAll(List.of("  Insurance   : " + (b.insurance ? "Yes" : "No"),
            "  Promo       : " + b.promo, "  SkyPoints   : " + b.pointsUsed + " redeemed", "  Payment     : " + b.pay, "  Booked on   : " + b.bookedAt.format(DT), l,
            "  TOTAL PAID  : " + Pricing.inr(b.total) + (b.cancelled ? "\n  REFUNDED    : " + Pricing.inr(b.refund) : ""), l,
            "  Please reach the airport " + (f.intl() ? "3 hours" : "2 hours") + " before departure.", "  Carry a valid photo ID" + (f.intl() ? " and passport/visa." : ".")));
        return String.join("\n", t);
    }
    static Path export(Booking b) throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), "SkyIndia_Tickets"); Files.createDirectories(dir);
        return Files.writeString(dir.resolve("Ticket_" + b.pnr + ".txt"), ticket(b));
    }
    /** Writes a PDF boarding pass for one leg and one passenger to ~/SkyIndia_Tickets. */
    static Path exportPdf(Booking b, int leg, String passenger) throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), "SkyIndia_Tickets"); Files.createDirectories(dir);
        String safe = passenger.replaceAll("[^A-Za-z0-9]", "_");
        return Files.write(dir.resolve("BoardingPass_" + b.pnr + "_leg" + (leg + 1) + "_" + safe + ".pdf"), Pdf.boardingPass(b, leg, passenger));
    }
}

/** One step on the trip timeline. kind: book, checkin, airport, board, gate, dep, arr, layover. */
record Event(LocalDateTime at, String title, String detail, String kind) {}

/** Builds the ordered timeline of a booking: check-in window, airport arrival, boarding, flights and connections. */
final class Timeline {
    static List<Event> of(Booking b) {
        List<Event> ev = new ArrayList<>();
        FareType ft = b.fare();
        ev.add(new Event(b.bookedAt, "Booking confirmed", "PNR " + b.pnr + " · " + b.tripType() + (ft == null ? "" : " · " + ft.label() + " fare"), "book"));
        List<Flight> legs = b.legs(); List<Integer> jr = b.jr();
        for (int i = 0; i < legs.size(); i++) {
            Flight f = legs.get(i);
            boolean firstOfJourney = i == 0 || !jr.get(i).equals(jr.get(i - 1));
            String gate = Bookings.gate(f), route = f.from().code() + " → " + f.to().code();
            if (firstOfJourney) {
                ev.add(new Event(f.dep().minusHours(48), "Web check-in opens", f.no() + " · " + route + (b.checkedIn(f) ? " · checked in" : ""), "checkin"));
                ev.add(new Event(f.dep().minusHours(f.intl() ? 3 : 2), "Arrive at the airport",
                        "Recommended " + (f.intl() ? "3 hours" : "2 hours") + " before departure · " + f.from().city() + " (" + f.from().code() + ")", "airport"));
                ev.add(new Event(f.dep().minusHours(1), "Check-in & bag drop close", f.no() + " · counters close 60 minutes before departure", "checkin"));
            } else {
                Flight p = legs.get(i - 1);
                long lay = ChronoUnit.MINUTES.between(p.arr(), f.dep());
                ev.add(new Event(p.arr(), "Connection at " + p.to().city(), "Layover " + Catalog.dur(lay) + " · next: " + f.no() + " to " + f.to().code(), "layover"));
            }
            ev.add(new Event(f.dep().minusMinutes(40), "Boarding starts", f.no() + " · Gate " + gate, "board"));
            ev.add(new Event(f.dep().minusMinutes(25), "Gate closes", "Be at Gate " + gate + " before this time", "gate"));
            ev.add(new Event(f.dep(), "Departs " + f.from().code(), f.airline() + " " + f.no() + " · " + route, "dep"));
            ev.add(new Event(f.arr(), "Arrives " + f.to().code(), f.to().city() + " · flight time " + Catalog.dur(f.mins()), "arr"));
        }
        ev.sort(Comparator.comparing(Event::at));
        return ev;
    }
}

/** Destination information: local time zone, typical weather, baggage and visa notes (reference data, not live). */
final class DestInfo {
    record Info(String zone, String currency, String plug, String visa, String baggage,
                double mean, double amp, double diurnal, int wetMask, String climate) {}
    record Weather(int hi, int lo, String cond, String note) {}

    private static final Map<String, Info> M = new HashMap<>();
    private static final String IN_VISA = "No visa needed for Indian citizens. Carry a valid government photo ID (Aadhaar, passport, driving licence or voter ID).";
    private static final String IN_BAG = "Cabin: one bag up to 7 kg. Power banks and spare lithium batteries must go in cabin baggage, never in check-in. "
            + "Liquids, lighters and sharp objects are restricted in the cabin.";
    private static final String GEN = " Power banks and spare lithium batteries: cabin only. Liquids in cabin bags: containers up to 100 ml.";
    private static final String CHECK = " Rules change often, so check the official embassy / immigration website before you fly.";

    private static int mask(int... months) { int m = 0; for (int x : months) m |= 1 << x; return m; }
    private static void put(String code, String zone, String cur, String plug, String visa, String bag, double mean, double amp,
                            double diurnal, int wet, String climate) {
        M.put(code, new Info(zone, cur, plug, visa, bag, mean, amp, diurnal, wet, climate));
    }
    static {
        String ist = "Asia/Kolkata";
        put("DEL", ist, "Indian rupee (INR)", "Type C/D/M, 230 V", IN_VISA, IN_BAG, 24, 9.5, 11, mask(7, 8, 9), "Hot summers, monsoon in Jul-Sep, cool winters with morning fog.");
        put("BOM", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 27, 2.5, 6, mask(6, 7, 8, 9), "Warm and humid all year, heavy monsoon Jun-Sep.");
        put("BLR", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 24, 2.5, 9, mask(5, 6, 7, 8, 9, 10), "Mild all year; rain mostly May-Oct.");
        put("HYD", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 27, 4, 10, mask(6, 7, 8, 9), "Hot summers, monsoon Jun-Sep, pleasant winters.");
        put("MAA", ist, "Indian rupee (INR)", "Type C/D/M, 230 V", IN_VISA, IN_BAG, 29, 3.5, 7, mask(10, 11, 12), "Hot and humid; the main rains come Oct-Dec.");
        put("CCU", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 27, 4.5, 8, mask(6, 7, 8, 9), "Humid, monsoon Jun-Sep, mild winters.");
        put("JAI", ist, "Indian rupee (INR)", "Type C/D/M, 230 V", IN_VISA, IN_BAG, 25, 9.5, 13, mask(7, 8, 9), "Dry and hot, short monsoon Jul-Sep, cool winters.");
        put("AMD", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 27, 8, 13, mask(7, 8, 9), "Very hot summers, monsoon Jul-Sep, dry winters.");
        put("GOI", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 27, 2, 7, mask(6, 7, 8, 9), "Beach weather Nov-Feb; heavy monsoon Jun-Sep.");
        put("COK", ist, "Indian rupee (INR)", "Type C/D, 230 V", IN_VISA, IN_BAG, 27.5, 1.5, 6, mask(6, 7, 8, 9, 10), "Warm and humid, long monsoon Jun-Oct.");
        put("DXB", "Asia/Dubai", "UAE dirham (AED)", "Type G, 230 V",
                "Indian passport holders generally need a UAE visa before travel (arranged online or through the airline). Some travellers holding a valid US, UK or Schengen visa or residence may qualify for visa on arrival. Passport should be valid for 6+ months." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Some prescription medicines (certain codeine, tramadol and sedative products) are controlled; carry the prescription. Duty-free liquor and cigarette allowances are limited.",
                27.5, 9, 10, 0, "Very hot summers; mild, pleasant winters. Rain is rare.");
        put("SIN", "Asia/Singapore", "Singapore dollar (SGD)", "Type G, 230 V",
                "Indian citizens generally need a Singapore visa for visits, arranged online through an authorised agent in advance. Passport valid 6+ months. A digital arrival card (SG Arrival Card) is required within 3 days before arrival." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Chewing gum cannot be imported and e-cigarettes / vapes are banned. Customs is strict about duty-free tobacco and liquor.",
                27, 0.8, 6, mask(11, 12, 1), "Hot and humid all year with frequent short showers; wetter Nov-Jan.");
        put("LHR", "Europe/London", "Pound sterling (GBP)", "Type G, 230 V",
                "Indian citizens need a UK Standard Visitor visa before travel; passing through some UK airports may also need a transit visa. Carry your visa, return ticket and proof of funds and accommodation." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Personal imports of meat, dairy and many plant products from outside the EU are restricted. Declare cash of GBP 10,000 or more.",
                11, 7, 7, mask(10, 11, 12, 1), "Cool and changeable; showers possible in any month, darker and wetter Oct-Jan.");
        put("BKK", "Asia/Bangkok", "Thai baht (THB)", "Type A/B/C/O, 230 V",
                "Thailand has offered visa-free short stays to Indian passport holders; the permitted length and conditions change, so confirm the current rule. A digital arrival card is required before entry, and passport validity of 6 months is expected." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " E-cigarettes and vaping devices are illegal to bring in. Carry prescriptions for medicines.",
                29, 2, 8, mask(5, 6, 7, 8, 9, 10), "Hot all year, wettest May-Oct; Nov-Feb is the most comfortable.");
        put("DOH", "Asia/Qatar", "Qatari riyal (QAR)", "Type D/G, 230 V",
                "Indian citizens are generally eligible for visa-free entry or a visa on arrival for short stays, usually needing a valid passport, return ticket and accommodation details. Conditions can change." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Alcohol and pork products are restricted; some medicines need a prescription; drones need prior approval.",
                27.5, 10, 10, 0, "Extremely hot summers, mild winters; rainfall is rare.");
        put("JFK", "America/New_York", "US dollar (USD)", "Type A/B, 120 V",
                "Indian citizens need a US visitor visa (B1/B2); the ESTA waiver is not available to Indian passport holders. Carry your visa, passport and return ticket." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Fresh fruit, vegetables, seeds and meat products are not allowed through customs. Declare cash of USD 10,000 or more.",
                13, 12, 8, 0, "Hot humid summers, cold winters with snow; rain can occur in any month.");
        put("CDG", "Europe/Paris", "Euro (EUR)", "Type C/E, 230 V",
                "Indian citizens need a Schengen visa for Paris. Travel medical insurance (at least EUR 30,000 cover) is required for the visa." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Meat and dairy products from outside the EU are restricted. Declare cash of EUR 10,000 or more.",
                12, 8.5, 8, 0, "Mild, changeable weather; warm summers and cold, damp winters.");
        put("KUL", "Asia/Kuala_Lumpur", "Malaysian ringgit (MYR)", "Type G, 240 V",
                "Malaysia has allowed visa-free short stays for Indian citizens under a time-limited arrangement, so confirm it is still in force. The Malaysia Digital Arrival Card (MDAC) must be completed before arrival." + CHECK,
                "Check-in allowance depends on your fare." + GEN + " Duty-free allowances for alcohol and tobacco are limited; vapes are regulated.",
                27, 0.7, 8, mask(4, 10, 11), "Hot and humid all year; afternoon thunderstorms, wettest Oct-Nov.");
    }

    static Info of(Airport a) { return M.get(a.code()); }
    static ZoneId zone(Airport a) { return ZoneId.of(of(a).zone()); }

    /** Typical (not live) weather for a month 1-12. */
    static Weather weather(Airport a, int month) {
        Info i = of(a);
        double t = i.mean() + i.amp() * Math.sin(2 * Math.PI * (month - 4) / 12.0);
        int hi = (int) Math.round(t + i.diurnal() / 2), lo = (int) Math.round(t - i.diurnal() / 2);
        boolean wet = ((i.wetMask() >> month) & 1) == 1;
        String cond = wet ? "Rain showers likely" : t >= 33 ? "Hot and sunny" : t >= 27 ? "Warm, mostly clear" : t >= 18 ? "Pleasant" : t >= 10 ? "Cool" : "Cold";
        String note = hi >= 36 ? "Carry sun protection and plenty of water." : hi <= 10 ? "Pack warm layers." : wet ? "Carry an umbrella or light rain jacket." : "Light layers should do.";
        return new Weather(hi, lo, cond, note);
    }
    /** "2h 30m behind India" / "same time as India" / "1h 30m ahead of India". */
    static String offsetVsIndia(Airport a) {
        Instant now = Instant.now();
        int mine = ZoneId.of("Asia/Kolkata").getRules().getOffset(now).getTotalSeconds();
        int there = zone(a).getRules().getOffset(now).getTotalSeconds();
        int diff = (there - mine) / 60;
        if (diff == 0) return "Same time as India";
        return Catalog.dur(Math.abs(diff)) + (diff > 0 ? " ahead of India" : " behind India");
    }
}

/** Admin console backend: stats, booking oversight and flight controls. Only meaningful for User.admin accounts. */
final class Admin {
    record Stats(int users, int bookings, int upcoming, int cancelled, double revenue, double refunds) {}

    static boolean is(User u) { return u != null && u.admin; }
    static List<User> users() {
        List<User> l = new ArrayList<>(Store.db.users.values()); l.sort(Comparator.comparing((User u) -> u.name.toLowerCase())); return l;
    }
    static List<Booking> bookings() {
        List<Booking> l = new ArrayList<>(Store.db.bookings); l.sort(Comparator.comparing((Booking b) -> b.bookedAt).reversed()); return l;
    }
    static Stats stats() {
        int up = 0, canc = 0; double rev = 0, ref = 0;
        for (Booking b : Store.db.bookings) {
            if (b.cancelled) { canc++; ref += b.refund; rev += b.total - b.refund; }
            else { rev += b.total; if (b.status().equals("Upcoming")) up++; }
        }
        return new Stats(Store.db.users.size(), Store.db.bookings.size(), up, canc, rev, ref);
    }
    /** Most-booked routes as "DEL → BOM" -> number of legs, highest first. */
    static LinkedHashMap<String, Integer> topRoutes(int n) {
        Map<String, Integer> m = new HashMap<>();
        for (Booking b : Store.db.bookings) if (!b.cancelled)
            for (Flight f : b.legs()) m.merge(f.from().code() + " → " + f.to().code(), 1, Integer::sum);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((x, y) -> y.getValue() - x.getValue());
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.put(es.get(i).getKey(), es.get(i).getValue());
        return out;
    }
    /** Net revenue (after refunds) by month the booking was made, oldest first, for the last n months. */
    static TreeMap<YearMonth, Double> revenueByMonth(int n) {
        TreeMap<YearMonth, Double> m = new TreeMap<>(); YearMonth now = YearMonth.now();
        for (int i = n - 1; i >= 0; i--) m.put(now.minusMonths(i), 0.0);
        for (Booking b : Store.db.bookings) m.computeIfPresent(YearMonth.from(b.bookedAt), (k, v) -> v + (b.cancelled ? b.total - b.refund : b.total));
        return m;
    }
    /** Cancels any booking on the customer's behalf (full refund by default). */
    static void cancelBooking(Booking b, boolean fullRefund) {
        if (b.cancelled) return;
        Bookings.cancel(b, fullRefund ? 1.0 : Bookings.refundPct(b));
    }
    static void setFare(String flightId, double fare) {
        if (fare <= 0) throw new IllegalArgumentException("Fare must be positive.");
        Store.db.fareOverride.put(flightId, fare); Store.save();
    }
    static void clearFare(String flightId) { Store.db.fareOverride.remove(flightId); Store.save(); }
    /** A grounded flight disappears from search results. */
    static void ground(String flightId, boolean grounded) {
        if (grounded) Store.db.grounded.add(flightId); else Store.db.grounded.remove(flightId);
        Store.save();
    }
    /** Free-text status shown to travellers, e.g. "Delayed 40 min" (blank clears it). */
    static void setStatus(String flightId, String text) {
        if (text == null || text.isBlank()) Store.db.flightStatus.remove(flightId); else Store.db.flightStatus.put(flightId, text.trim());
        Store.save();
    }
    static String status(String flightId) { return Store.db.flightStatus.get(flightId); }
}

/** Minimal dependency-free PDF writer used for the boarding pass (decorative bars, not a scannable code). */
final class Pdf {
    private final StringBuilder c = new StringBuilder();

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '\\') b.append('\\').append(ch);
            else if (ch >= 32 && ch < 127) b.append(ch); else b.append('?');
        }
        return b.toString();
    }
    private void rect(double x, double y, double w, double h, double r, double g, double b) {
        c.append(String.format(Locale.ROOT, "%.3f %.3f %.3f rg %.1f %.1f %.1f %.1f re f\n", r, g, b, x, y, w, h));
    }
    private void text(double x, double y, double size, boolean bold, double r, double g, double b, String s) {
        c.append(String.format(Locale.ROOT, "BT /%s %.1f Tf %.3f %.3f %.3f rg %.1f %.1f Td (%s) Tj ET\n", bold ? "F2" : "F1", size, r, g, b, x, y, esc(s)));
    }
    private void line(double x1, double y1, double x2, double y2, double r, double g, double b, double w, boolean dashed) {
        c.append(String.format(Locale.ROOT, "%.3f %.3f %.3f RG %.1f w %s %.1f %.1f m %.1f %.1f l S [] 0 d\n", r, g, b, w, dashed ? "[3 3] 0 d" : "[] 0 d", x1, y1, x2, y2));
    }
    private void tri(double x1, double y1, double x2, double y2, double x3, double y3, double r, double g, double b) {
        c.append(String.format(Locale.ROOT, "%.3f %.3f %.3f rg %.1f %.1f m %.1f %.1f l %.1f %.1f l f\n", r, g, b, x1, y1, x2, y2, x3, y3));
    }

    static byte[] boardingPass(Booking b, int leg, String passenger) {
        Flight f = b.legs().get(leg); Pdf p = new Pdf();
        double[] ink = {0.06, 0.09, 0.16}, gray = {0.39, 0.45, 0.55}, ind = {0.31, 0.27, 0.90};
        p.rect(0, 0, 595, 270, 1, 1, 1);
        p.rect(0, 210, 595, 60, 0.07, 0.09, 0.15);
        p.rect(0, 205, 595, 5, ind[0], ind[1], ind[2]);
        p.text(30, 238, 22, true, 1, 1, 1, "SKYINDIA");
        p.text(30, 222, 9, false, 0.78, 0.82, 0.99, "BOARDING PASS  -  " + (b.legs().size() > 1 ? "FLIGHT " + (leg + 1) + " OF " + b.legs().size() : f.airline()));
        p.text(470, 243, 9, false, 0.78, 0.82, 0.99, "PNR");
        p.text(470, 220, 22, true, 1, 1, 1, b.pnr);

        p.text(30, 160, 44, true, ink[0], ink[1], ink[2], f.from().code());
        p.text(30, 142, 11, false, gray[0], gray[1], gray[2], f.from().city());
        p.text(30, 124, 16, true, ind[0], ind[1], ind[2], f.dep().format(DateTimeFormatter.ofPattern("HH:mm")));
        p.text(30, 110, 9, false, gray[0], gray[1], gray[2], f.dep().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        p.text(240, 160, 44, true, ink[0], ink[1], ink[2], f.to().code());
        p.text(240, 142, 11, false, gray[0], gray[1], gray[2], f.to().city());
        p.text(240, 124, 16, true, ind[0], ind[1], ind[2], f.arr().format(DateTimeFormatter.ofPattern("HH:mm")));
        p.text(240, 110, 9, false, gray[0], gray[1], gray[2], f.arr().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        p.line(158, 170, 226, 170, 0.8, 0.84, 0.88, 1.5, true);
        p.tri(220, 175, 230, 170, 220, 165, ind[0], ind[1], ind[2]);
        p.text(168, 177, 9, true, ink[0], ink[1], ink[2], (f.mins() / 60) + "h " + String.format("%02d", f.mins() % 60) + "m");

        String[][] row1 = {{"FLIGHT", f.no()}, {"CABIN", b.cls}, {"SEATS", String.join(", ", b.legSeats(leg))}, {"GATE", Bookings.gate(f)}};
        double[] xs = {30, 120, 235, 330};
        for (int i = 0; i < row1.length; i++) {
            p.text(xs[i], 84, 8, false, gray[0], gray[1], gray[2], row1[i][0]);
            p.text(xs[i], 68, 11, true, ink[0], ink[1], ink[2], row1[i][1]);
        }
        p.text(30, 44, 8, false, gray[0], gray[1], gray[2], "PASSENGER");
        p.text(30, 28, 12, true, ink[0], ink[1], ink[2], passenger);
        p.text(235, 44, 8, false, gray[0], gray[1], gray[2], "BOARDING");
        p.text(235, 28, 12, true, ink[0], ink[1], ink[2], f.dep().minusMinutes(40).format(DateTimeFormatter.ofPattern("HH:mm")));
        p.text(330, 44, 8, false, gray[0], gray[1], gray[2], "STATUS");
        p.text(330, 28, 12, true, ink[0], ink[1], ink[2], b.checkedIn(f) ? "CHECKED IN" : b.status().toUpperCase());

        p.line(405, 20, 405, 195, 0.8, 0.84, 0.88, 1, true);
        Random r = new Random((b.pnr + f.id()).hashCode()); int x = 420;
        while (x < 565) { int w = 1 + r.nextInt(3); p.rect(x, 34, w, 60, ink[0], ink[1], ink[2]); x += w + 1 + r.nextInt(3); }
        p.text(420, 20, 8, false, gray[0], gray[1], gray[2], b.pnr + "  " + f.no());
        p.text(420, 110, 8, false, gray[0], gray[1], gray[2], "Gate closes 25 min before departure.");
        p.text(420, 120, 8, false, gray[0], gray[1], gray[2], "Carry a valid photo ID" + (f.intl() ? " + passport." : "."));
        return p.build();
    }

    private static void w(ByteArrayOutputStream o, String s) { o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }

    private byte[] build() {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); int[] off = new int[7];
        w(o, "%PDF-1.4\n");
        off[1] = o.size(); w(o, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        off[2] = o.size(); w(o, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        off[3] = o.size(); w(o, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 270] /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 4 0 R >>\nendobj\n");
        byte[] content = c.toString().getBytes(StandardCharsets.ISO_8859_1);
        off[4] = o.size(); w(o, "4 0 obj\n<< /Length " + content.length + " >>\nstream\n"); o.writeBytes(content); w(o, "\nendstream\nendobj\n");
        off[5] = o.size(); w(o, "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");
        off[6] = o.size(); w(o, "6 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj\n");
        int xref = o.size();
        w(o, "xref\n0 7\n0000000000 65535 f \n");
        for (int i = 1; i <= 6; i++) w(o, String.format(Locale.ROOT, "%010d 00000 n \n", off[i]));
        w(o, "trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return o.toByteArray();
    }
}