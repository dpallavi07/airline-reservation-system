import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * Extras.java - SkyIndia UI widgets and helpers (confetti, charts, status tracker, notifications,
 * saved travellers, payment form, invoice, help page).
 */
final class Extras {
    private Extras() { }

    static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static String compact(double v) { return v >= 1000 ? "₹" + Math.round(v / 100) / 10.0 + "k" : "₹" + Math.round(v); }

    /** Theme-aware checkbox. */
    static class CB extends JCheckBox {
        CB(String t) { super(t); setForeground(SkyIndia.INK); }
        CB(String t, boolean s) { super(t, s); setForeground(SkyIndia.INK); }
    }

    // =========================================================
    // CONFETTI (glass pane)
    // =========================================================
    static class Confetti extends JComponent {
        static final int N = 150;
        static final Color[] PAL = {new Color(0x4F46E5), new Color(0x7C3AED), new Color(0x0EA5E9),
                new Color(0x10B981), new Color(0xF59E0B), new Color(0xEF4444)};
        final float[] x = new float[N], y = new float[N], vx = new float[N], vy = new float[N], rot = new float[N], vr = new float[N];
        final Color[] col = new Color[N];
        boolean pending;
        int frame;
        javax.swing.Timer t;

        Confetti() { setOpaque(false); setVisible(false); }
        @Override public boolean contains(int px, int py) { return false; } // never blocks clicks

        void burst() {
            pending = true;
            frame = 0;
            setVisible(true);
            if (t != null) t.stop();
            t = new javax.swing.Timer(16, e -> step());
            t.start();
        }
        void init() {
            Random r = new Random();
            for (int i = 0; i < N; i++) {
                x[i] = getWidth() * (0.3f + 0.4f * r.nextFloat());
                y[i] = getHeight() * 0.30f;
                double a = -Math.PI / 2 + (r.nextDouble() - 0.5) * 2.4, sp = 5 + r.nextDouble() * 11;
                vx[i] = (float) (Math.cos(a) * sp);
                vy[i] = (float) (Math.sin(a) * sp);
                rot[i] = r.nextFloat() * 6;
                vr[i] = (r.nextFloat() - .5f) * .4f;
                col[i] = PAL[r.nextInt(PAL.length)];
            }
            pending = false;
        }
        void step() {
            if (pending) { if (getWidth() == 0) return; init(); }
            for (int i = 0; i < N; i++) { vy[i] += 0.32f; vx[i] *= 0.992f; x[i] += vx[i]; y[i] += vy[i]; rot[i] += vr[i]; }
            if (++frame > 170) { t.stop(); setVisible(false); }
            repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            if (pending || col[0] == null) return;
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            int alpha = frame > 120 ? Math.max(0, (int) (255 * (1 - (frame - 120) / 50f))) : 255;
            for (int i = 0; i < N; i++) {
                g.setColor(new Color(col[i].getRed(), col[i].getGreen(), col[i].getBlue(), alpha));
                AffineTransform old = g.getTransform();
                g.translate(x[i], y[i]);
                g.rotate(rot[i]);
                g.fillRect(-4, -2, 8, 4);
                g.setTransform(old);
            }
            g.dispose();
        }
    }

    // =========================================================
    // STAGGERED SLIDE-IN WRAPPER
    // =========================================================
    static class Reveal extends JPanel {
        float p;
        javax.swing.Timer t;
        Reveal(JComponent c, int index) {
            super(new BorderLayout());
            setOpaque(false);
            setAlignmentX(0f);
            add(c);
            javax.swing.Timer start = new javax.swing.Timer(Math.min(index, 10) * 70, e -> {
                t = new javax.swing.Timer(16, ev -> { p = Math.min(1, p + 0.08f); repaint(); if (p >= 1) t.stop(); });
                t.start();
            });
            start.setRepeats(false);
            start.start();
        }
        @Override protected void paintChildren(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            float e = 1 - (1 - p) * (1 - p) * (1 - p);
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, e))));
            g.translate((1 - e) * 60, 0);
            super.paintChildren(g);
            g.dispose();
        }
    }

    // =========================================================
    // SKELETON LOADING CARDS
    // =========================================================
    static class Skeleton extends JComponent {
        static final int CARDS = 5, STEP = 126;
        float ph;
        javax.swing.Timer t;
        Skeleton() {
            setPreferredSize(new Dimension(600, CARDS * STEP));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, CARDS * STEP));
            setAlignmentX(0f);
        }
        @Override public void addNotify() {
            super.addNotify();
            t = new javax.swing.Timer(20, e -> { ph = (ph + 0.014f) % 1f; repaint(); });
            t.start();
        }
        @Override public void removeNotify() { if (t != null) t.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            int w = getWidth();
            float cx = ph * (w + 400) - 200;
            Color base = SkyIndia.dark ? new Color(0x2B3650) : new Color(0xE5E9F2);
            Color hi = SkyIndia.dark ? new Color(0x3B4868) : new Color(0xF6F8FC);
            Paint shimmer = new LinearGradientPaint(new Point2D.Float(cx - 140, 0), new Point2D.Float(cx + 140, 0),
                    new float[]{0f, .5f, 1f}, new Color[]{base, hi, base});
            for (int i = 0; i < CARDS; i++) {
                int y = i * STEP;
                g.setColor(SkyIndia.CARD);
                g.fillRoundRect(0, y, w - 1, 114, 18, 18);
                g.setColor(SkyIndia.LINE);
                g.drawRoundRect(0, y, w - 1, 114, 18, 18);
                g.setPaint(shimmer);
                g.fillOval(22, y + 24, 42, 42);
                g.fillRoundRect(78, y + 26, 130, 12, 8, 8);
                g.fillRoundRect(78, y + 48, 74, 10, 8, 8);
                g.fillRoundRect(78, y + 78, 90, 12, 8, 8);
                g.fillRoundRect(w / 2 - 150, y + 30, 70, 22, 8, 8);
                g.fillRoundRect(w / 2 - 50, y + 40, 100, 6, 6, 6);
                g.fillRoundRect(w / 2 + 80, y + 30, 70, 22, 8, 8);
                g.fillRoundRect(w - 190, y + 24, 140, 22, 8, 8);
                g.fillRoundRect(w - 170, y + 62, 120, 32, 12, 12);
            }
            g.dispose();
        }
    }

    // =========================================================
    // CHARTS
    // =========================================================
    static class TrendChart extends JComponent {
        final double[] v;
        final LocalDate start;
        TrendChart(double[] v, LocalDate start) {
            this.v = v;
            this.start = start;
            setPreferredSize(new Dimension(600, 150));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            int w = getWidth(), h = getHeight(), l = 58, r = 14, t = 10, b = 24, n = v.length;
            if (n < 2 || h - b - t < 10) { g.dispose(); return; }
            double min = Arrays.stream(v).min().orElse(0), max = Arrays.stream(v).max().orElse(1);
            if (max - min < 1) max = min + 1;
            double pad = (max - min) * 0.15;
            min -= pad;
            max += pad;
            g.setFont(SkyIndia.f(SkyIndia.P, 10));
            for (int k = 0; k < 3; k++) {
                double val = min + (max - min) * k / 2.0;
                int y = (int) (h - b - (h - b - t) * (val - min) / (max - min));
                g.setColor(SkyIndia.LINE);
                g.drawLine(l, y, w - r, y);
                g.setColor(SkyIndia.MUTED);
                g.drawString(SkyIndia.inr(val), 4, y + 4);
            }
            Path2D line = new Path2D.Double(), area = new Path2D.Double();
            int mi = 0;
            for (int i = 0; i < n; i++) {
                double x = l + (w - l - r) * (double) i / (n - 1);
                double y = h - b - (h - b - t) * (v[i] - min) / (max - min);
                if (i == 0) { line.moveTo(x, y); area.moveTo(x, h - b); area.lineTo(x, y); }
                else { line.lineTo(x, y); area.lineTo(x, y); }
                if (v[i] < v[mi]) mi = i;
            }
            area.lineTo(w - r, h - b);
            area.closePath();
            g.setPaint(new GradientPaint(0, t, new Color(79, 70, 229, 70), 0, h - b, new Color(79, 70, 229, 0)));
            g.fill(area);
            g.setColor(SkyIndia.PRIMARY);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);
            double mx = l + (w - l - r) * (double) mi / (n - 1), my = h - b - (h - b - t) * (v[mi] - min) / (max - min);
            g.setColor(SkyIndia.OK);
            g.fill(new Ellipse2D.Double(mx - 5, my - 5, 10, 10));
            String tag = "Lowest " + SkyIndia.inr(v[mi]) + " · " + start.plusDays(mi).format(SkyIndia.SHORT);
            g.setFont(SkyIndia.f(SkyIndia.B, 11));
            int tw = g.getFontMetrics().stringWidth(tag);
            g.drawString(tag, (int) Math.max(l, Math.min(w - r - tw, mx - tw / 2.0)), (int) Math.max(t + 10, my - 10));
            g.setFont(SkyIndia.f(SkyIndia.P, 10));
            g.setColor(SkyIndia.MUTED);
            for (int i : new int[]{0, n / 2, n - 1}) {
                String s = start.plusDays(i).format(SkyIndia.SHORT);
                int x = (int) (l + (w - l - r) * (double) i / (n - 1)) - g.getFontMetrics().stringWidth(s) / 2;
                g.drawString(s, Math.max(0, Math.min(w - g.getFontMetrics().stringWidth(s), x)), h - 6);
            }
            g.dispose();
        }
    }

    static class BarChart extends JComponent {
        final String[] labels;
        final double[] v;
        BarChart(String[] labels, double[] v) {
            this.labels = labels;
            this.v = v;
            setPreferredSize(new Dimension(500, 170));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            int w = getWidth(), h = getHeight(), n = v.length;
            double max = Math.max(1, Arrays.stream(v).max().orElse(1));
            int slot = (w - 20) / n, bw = (int) (slot * 0.5), base = h - 24, top = 24;
            for (int i = 0; i < n; i++) {
                int cx = 10 + slot * i + slot / 2;
                int bh = (int) ((base - top) * v[i] / max);
                if (v[i] > 0) {
                    g.setPaint(new GradientPaint(0, base - bh, SkyIndia.PRIMARY2, 0, base, SkyIndia.PRIMARY));
                    g.fillRoundRect(cx - bw / 2, base - bh, bw, bh, 10, 10);
                    g.setFont(SkyIndia.f(SkyIndia.B, 11));
                    g.setColor(SkyIndia.INK);
                    String s = compact(v[i]);
                    g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, base - bh - 5);
                } else {
                    g.setColor(SkyIndia.LINE);
                    g.fillRoundRect(cx - bw / 2, base - 4, bw, 4, 4, 4);
                }
                g.setFont(SkyIndia.f(SkyIndia.P, 11));
                g.setColor(SkyIndia.MUTED);
                g.drawString(labels[i], cx - g.getFontMetrics().stringWidth(labels[i]) / 2, h - 6);
            }
            g.dispose();
        }
    }

    /** Price trend for the next 30 days (cheapest option each day, direct or 1-stop). */
    static JPanel trend(Airport a, Airport b, double mult) {
        double[] v = new double[30];
        LocalDate start = LocalDate.now().plusDays(1);
        for (int i = 0; i < v.length; i++)
            v[i] = Catalog.options(a, b, start.plusDays(i)).stream().mapToDouble(Option::fare).min().orElse(0) * mult;
        SkyIndia.Round card = new SkyIndia.Round(18, SkyIndia.CARD).shadow();
        card.setBorder(new EmptyBorder(14, 18, 12, 18));
        card.setLayout(new BorderLayout(0, 6));
        card.add(SkyIndia.lbl("Price trend · next 30 days", SkyIndia.B, 14, SkyIndia.INK), BorderLayout.NORTH);
        card.add(new TrendChart(v, start), BorderLayout.CENTER);
        return card;
    }

    static JPanel analytics(List<Booking> all) {
        int n = 6;
        String[] labels = new String[n];
        double[] v = new double[n];
        YearMonth now = YearMonth.now();
        int trips = 0;
        for (int i = 0; i < n; i++) {
            YearMonth ym = now.minusMonths(2).plusMonths(i);
            labels[i] = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            for (Booking b : all)
                if (!b.cancelled && YearMonth.from(b.flight.dep()).equals(ym)) { v[i] += b.total; trips++; }
        }
        SkyIndia.Round card = new SkyIndia.Round(20, SkyIndia.CARD).shadow();
        card.setBorder(new EmptyBorder(18, 22, 18, 22));
        card.setLayout(new BorderLayout(0, 8));
        card.add(SkyIndia.vbox(SkyIndia.lbl("Spend by travel month", SkyIndia.B, 17, SkyIndia.INK), SkyIndia.gap(3),
                SkyIndia.lbl(trips + " trip" + (trips == 1 ? "" : "s") + " in this 6-month window", SkyIndia.P, 12, SkyIndia.MUTED)), BorderLayout.NORTH);
        card.add(new BarChart(labels, v), BorderLayout.CENTER);
        return card;
    }

    // =========================================================
    // FLIGHT STATUS (simulated)
    // =========================================================
    static class Status {
        final String text, gate;
        final Color color;
        final double progress;
        Status(String text, Color color, String gate, double progress) { this.text = text; this.color = color; this.gate = gate; this.progress = progress; }
    }

    static Status status(Flight fx) {
        int h = Math.abs(String.valueOf(fx.id()).hashCode() % 100000);
        int delay = h % 4 == 0 ? 10 + (h / 4 % 4) * 5 : 0;
        String gate = (char) ('A' + h % 6) + String.valueOf(1 + h / 6 % 24);
        LocalDateTime now = LocalDateTime.now(), dep = fx.dep().plusMinutes(delay), arr = fx.arr().plusMinutes(delay);
        if (now.isAfter(arr)) return new Status("Landed", SkyIndia.OK, gate, 1);
        if (now.isAfter(dep)) {
            double p = Duration.between(dep, now).toMinutes() / (double) Math.max(1, Duration.between(dep, arr).toMinutes());
            return new Status("In air", SkyIndia.BLUE, gate, Math.min(1, p));
        }
        if (now.isAfter(dep.minusMinutes(45))) return new Status("Boarding", SkyIndia.AMBER, gate, 0);
        return delay > 0 ? new Status("Delayed " + delay + " min", SkyIndia.AMBER, gate, 0) : new Status("On time", SkyIndia.OK, gate, 0);
    }

    static class Track extends JComponent {
        final double p;
        final String a, b;
        Track(double p, String a, String b) {
            this.p = p; this.a = a; this.b = b;
            setPreferredSize(new Dimension(300, 34));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            int w = getWidth(), y = 12, x0 = 34, x1 = w - 34;
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(SkyIndia.LINE);
            g.drawLine(x0, y, x1, y);
            int px = (int) (x0 + (x1 - x0) * p);
            g.setColor(SkyIndia.PRIMARY);
            g.drawLine(x0, y, px, y);
            g.setStroke(new BasicStroke(1f));
            g.setFont(SkyIndia.f(SkyIndia.B, 11));
            g.setColor(SkyIndia.MUTED);
            g.drawString(a, 0, y + 22);
            g.drawString(b, w - g.getFontMetrics().stringWidth(b), y + 22);
            SkyIndia.plane(g, px, y, 9, 0, SkyIndia.PRIMARY);
            g.dispose();
        }
    }

    static JPanel statusPanel(Flight fx) {
        Status s = status(fx);
        long mins = Duration.between(LocalDateTime.now(), fx.dep()).toMinutes();
        String cd = mins > 0 ? "Departs in " + (mins / 1440 > 0 ? mins / 1440 + "d " : "") + (mins % 1440 / 60) + "h" : "";
        JPanel top = SkyIndia.flow(FlowLayout.LEFT, 10, SkyIndia.pill(s.text.toUpperCase(), s.color),
                SkyIndia.lbl("Gate " + s.gate, SkyIndia.B, 12, SkyIndia.INK), SkyIndia.lbl(cd, SkyIndia.P, 12, SkyIndia.MUTED));
        top.setToolTipText("Simulated live status");
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(10, 0, 0, 0));
        p.add(top, BorderLayout.NORTH);
        p.add(new Track(s.progress, fx.from().code(), fx.to().code()), BorderLayout.CENTER);
        return p;
    }

    // =========================================================
    // LOYALTY
    // =========================================================
    static class Bar extends JComponent {
        final double p;
        final Color c;
        Bar(double p, Color c) { this.p = p; this.c = c; setPreferredSize(new Dimension(200, 10)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 10)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            g.setColor(SkyIndia.LINE);
            g.fillRoundRect(0, 0, getWidth(), 10, 10, 10);
            g.setColor(c);
            g.fillRoundRect(0, 0, (int) (getWidth() * Math.max(0.03, Math.min(1, p))), 10, 10, 10);
            g.dispose();
        }
    }

    static Color tierColor(String tier) {
        return tier.equals("Platinum") ? SkyIndia.PRIMARY2 : tier.equals("Gold") ? SkyIndia.AMBER : new Color(0x94A3B8);
    }

    /** SkyMiles card: tier, spendable balance, progress to the next tier. */
    static JPanel loyalty(User u, Runnable openRewards) {
        Loyalty.Tier t = Loyalty.of(u.email);
        int bal = Rewards.balance(u.email), pts = t.points();
        int lo = t.name().equals("Platinum") ? 600 : t.name().equals("Gold") ? 200 : 0;
        int hi = t.nextName().isEmpty() ? lo : t.nextAt();
        Color c = tierColor(t.name());
        String perks = t.name().equals("Platinum") ? "5% member discount on every booking" : t.name().equals("Gold") ? "3% member discount on every booking" : "Earn 1 SkyPoint per ₹100";
        SkyIndia.Round card = new SkyIndia.Round(22, SkyIndia.CARD).shadow();
        card.setBorder(new EmptyBorder(20, 24, 22, 24));
        card.setLayout(new BorderLayout(0, 12));
        SkyIndia.Btn more = new SkyIndia.Btn("Spend points & refer friends  →", 2);
        more.addActionListener(e -> openRewards.run());
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(SkyIndia.vbox(SkyIndia.lbl("SKYMILES", SkyIndia.B, 10, SkyIndia.MUTED), SkyIndia.gap(4),
                SkyIndia.flow(FlowLayout.LEFT, 10, SkyIndia.lbl(bal + " points to spend", SkyIndia.B, 24, SkyIndia.INK), SkyIndia.pill(t.name().toUpperCase(), c))), BorderLayout.WEST);
        JPanel mw = new JPanel(new GridBagLayout());
        mw.setOpaque(false);
        mw.add(more);
        head.add(mw, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        double prog = hi == lo ? 1 : (pts - lo) / (double) (hi - lo);
        card.add(SkyIndia.vbox(new Bar(prog, c), SkyIndia.gap(6),
                SkyIndia.lbl(t.nextName().isEmpty() ? "You're at the top tier · " + pts + " lifetime points." : (hi - pts) + " more lifetime points to " + t.nextName() + " · " + pts + " so far", SkyIndia.P, 12, SkyIndia.MUTED), SkyIndia.gap(8),
                SkyIndia.lbl("Perks: " + perks + " · 1 point = ₹1 off a fare", SkyIndia.B, 12, SkyIndia.INK)), BorderLayout.CENTER);
        return card;
    }

    // =========================================================
    // NOTIFICATIONS
    // =========================================================
    static List<String[]> notifications(String email, List<Booking> bs) {
        List<String[]> l = new ArrayList<>(SavedRoutes.triggered(email));
        LocalDateTime now = LocalDateTime.now();
        for (Booking b : bs) {
            String route = b.chain();
            if (b.cancelled) { l.add(new String[]{"Booking cancelled", b.pnr + " · " + route + " · refund " + SkyIndia.inr(b.refund)}); continue; }
            if (!b.status().equals("Upcoming")) continue;
            Flight fx = Bookings.nextLeg(b);
            if (fx == null) continue;
            long hrs = Duration.between(now, fx.dep()).toHours();
            if (hrs <= 24) l.add(new String[]{"Flight reminder", fx.from().code() + " → " + fx.to().code() + " departs in " + hrs + "h · Gate " + status(fx).gate});
            else if (Bookings.checkinOpen(b)) l.add(new String[]{"Web check-in is open", b.pnr + " · " + route});
            l.add(new String[]{"Booking confirmed", "PNR " + b.pnr + " · " + route + " · " + b.legs().get(0).dep().format(SkyIndia.SD)});
        }
        return l.size() > 12 ? l.subList(0, 12) : l;
    }

    static class Bell extends JButton {
        final Supplier<List<String[]>> src;
        int count;
        Bell(Supplier<List<String[]>> src) {
            this.src = src;
            count = src.get().size();
            setPreferredSize(new Dimension(38, 38));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Notifications");
            addActionListener(e -> popup());
        }
        void popup() {
            List<String[]> items = src.get();
            count = items.size();
            repaint();
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(SkyIndia.CARD);
            p.setBorder(new EmptyBorder(8, 8, 8, 8));
            p.add(SkyIndia.lbl("Notifications", SkyIndia.B, 14, SkyIndia.INK));
            p.add(SkyIndia.gap(8));
            if (items.isEmpty()) p.add(SkyIndia.lbl("You're all caught up.", SkyIndia.P, 12, SkyIndia.MUTED));
            for (String[] it : items) {
                JPanel row = SkyIndia.vbox(SkyIndia.lbl(it[0], SkyIndia.B, 12, SkyIndia.INK),
                        SkyIndia.lbl("<html><div style='width:280px'>" + it[1] + "</div></html>", SkyIndia.P, 11, SkyIndia.MUTED));
                row.setBorder(new EmptyBorder(6, 4, 6, 4));
                row.setAlignmentX(0f);
                p.add(row);
            }
            JScrollPane sp = new JScrollPane(p);
            sp.setBorder(new LineBorder(SkyIndia.LINE));
            sp.setPreferredSize(new Dimension(340, Math.min(360, p.getPreferredSize().height + 6)));
            JPopupMenu m = new JPopupMenu();
            m.add(sp);
            m.show(this, getWidth() - 340, getHeight());
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            g.translate(getWidth() / 2.0, getHeight() / 2.0);
            g.setColor(SkyIndia.MUTED);
            Path2D p = new Path2D.Double();
            p.moveTo(-8, 6);
            p.curveTo(-8, 6, -6, 2, -6, -3);
            p.curveTo(-6, -9, 6, -9, 6, -3);
            p.curveTo(6, 2, 8, 6, 8, 6);
            p.closePath();
            g.fill(p);
            g.fillOval(-2, 7, 4, 4);
            if (count > 0) {
                g.setColor(SkyIndia.DANGER);
                g.fillOval(3, -14, 14, 14);
                g.setColor(Color.WHITE);
                g.setFont(SkyIndia.f(SkyIndia.B, 9));
                String s = count > 9 ? "9+" : String.valueOf(count);
                g.drawString(s, 10 - g.getFontMetrics().stringWidth(s) / 2f, -4);
            }
            g.dispose();
        }
    }

    static JComponent bell(Supplier<List<String[]>> src) { return new Bell(src); }

    // =========================================================
    // SAVED TRAVELLERS
    // =========================================================
    static Path tfile(String email) {
        return Path.of(System.getProperty("user.home"), ".skyindia", "travellers-" + Math.abs(email.toLowerCase().hashCode()) + ".txt");
    }

    static List<String[]> saved(String email) {
        List<String[]> l = new ArrayList<>();
        try {
            Path p = tfile(email);
            if (Files.exists(p))
                for (String line : Files.readAllLines(p)) {
                    String[] a = line.split("\\|");
                    if (a.length == 2) l.add(a);
                }
        } catch (IOException ignored) { }
        return l;
    }

    static void saveTraveller(String email, String name, String age) {
        name = name.replace("|", " ").trim();
        List<String[]> l = saved(email);
        final String nm = name;
        l.removeIf(a -> a[0].equalsIgnoreCase(nm));
        l.add(new String[]{name, age});
        try {
            Path p = tfile(email);
            Files.createDirectories(p.getParent());
            List<String> lines = new ArrayList<>();
            for (String[] a : l) lines.add(a[0] + "|" + a[1]);
            Files.write(p, lines);
        } catch (IOException ignored) { }
    }

    static JComponent autofill(String email, JTextField name, JTextField age) {
        List<String[]> l = saved(email);
        if (l.isEmpty()) return new JLabel();
        String[] items = new String[l.size() + 1];
        items[0] = "Autofill from saved travellers…";
        for (int i = 0; i < l.size(); i++) items[i + 1] = l.get(i)[0] + " (" + l.get(i)[1] + ")";
        JComboBox<String> box = SkyIndia.combo(items, 260);
        box.setMaximumSize(new Dimension(260, 36));
        box.addActionListener(e -> {
            int i = box.getSelectedIndex();
            if (i > 0) { name.setText(l.get(i - 1)[0]); age.setText(l.get(i - 1)[1]); }
        });
        JPanel w = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        w.setOpaque(false);
        w.add(box);
        w.setAlignmentX(0f);
        w.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        return w;
    }

    // =========================================================
    // PAYMENT FORM + PROCESSING ANIMATION
    // =========================================================
    static void fmt(JTextField t, int max, int group, String sep) {
        ((AbstractDocument) t.getDocument()).setDocumentFilter(new DocumentFilter() {
            void apply(FilterBypass fb, int off, int len, String ins) throws BadLocationException {
                String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
                String next = cur.substring(0, off) + (ins == null ? "" : ins) + cur.substring(off + len);
                String d = next.replaceAll("\\D", "");
                if (d.length() > max) d = d.substring(0, max);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.length(); i++) { if (i > 0 && i % group == 0) sb.append(sep); sb.append(d.charAt(i)); }
                fb.replace(0, cur.length(), sb.toString(), null);
            }
            @Override public void replace(FilterBypass fb, int off, int len, String text, AttributeSet a) throws BadLocationException { apply(fb, off, len, text); }
            @Override public void insertString(FilterBypass fb, int off, String text, AttributeSet a) throws BadLocationException { apply(fb, off, 0, text); }
            @Override public void remove(FilterBypass fb, int off, int len) throws BadLocationException { apply(fb, off, len, ""); }
        });
    }

    static class PaymentForm {
        final JPanel panel = new JPanel(new CardLayout());
        final JTextField card = SkyIndia.field(), exp = SkyIndia.field(), cvv = SkyIndia.field(), upi = SkyIndia.field();
        String method = "UPI";

        PaymentForm() {
            panel.setOpaque(false);
            panel.setAlignmentX(0f);
            fmt(card, 16, 4, " ");
            fmt(exp, 4, 2, "/");
            fmt(cvv, 3, 99, "");
            card.setToolTipText("16-digit card number");
            JPanel two = new JPanel(new GridLayout(1, 2, 10, 0));
            two.setOpaque(false);
            two.add(SkyIndia.labeled("Expiry (MM/YY)", exp));
            two.add(SkyIndia.labeled("CVV", cvv));
            panel.add(SkyIndia.vbox(SkyIndia.labeled("UPI ID", upi), SkyIndia.gap(4), SkyIndia.lbl("e.g. name@okhdfc", SkyIndia.P, 11, SkyIndia.MUTED)), "UPI");
            panel.add(SkyIndia.vbox(SkyIndia.labeled("Card number", card), SkyIndia.gap(8), two), "Credit / Debit Card");
            panel.add(SkyIndia.vbox(SkyIndia.labeled("Bank", SkyIndia.combo(new String[]{"HDFC Bank", "SBI", "ICICI Bank", "Axis Bank", "Kotak"}, 200))), "Net Banking");
            panel.add(SkyIndia.vbox(SkyIndia.labeled("Wallet", SkyIndia.combo(new String[]{"Paytm", "PhonePe", "Amazon Pay"}, 200))), "Wallet");
        }
        void select(String m) {
            method = m;
            ((CardLayout) panel.getLayout()).show(panel, m);
            panel.revalidate();
            panel.repaint();
        }
        String validate() {
            switch (method) {
                case "UPI":
                    if (!upi.getText().trim().matches("[\\w.\\-]{2,}@[a-zA-Z]{2,}")) return "Enter a valid UPI ID, e.g. name@okhdfc.";
                    break;
                case "Credit / Debit Card":
                    if (card.getText().replaceAll("\\D", "").length() != 16) return "Card number must be 16 digits.";
                    String e = exp.getText();
                    if (!e.matches("(0[1-9]|1[0-2])/\\d{2}")) return "Enter the card expiry as MM/YY.";
                    if (YearMonth.of(2000 + Integer.parseInt(e.substring(3)), Integer.parseInt(e.substring(0, 2))).isBefore(YearMonth.now())) return "This card has expired.";
                    if (!cvv.getText().matches("\\d{3}")) return "CVV must be 3 digits.";
                    break;
                default:
                    break;
            }
            return null;
        }
    }

    static class Spinner extends JComponent {
        float a;
        javax.swing.Timer t;
        Spinner() { setPreferredSize(new Dimension(56, 56)); }
        @Override public void addNotify() { super.addNotify(); t = new javax.swing.Timer(16, e -> { a += 7; repaint(); }); t.start(); }
        @Override public void removeNotify() { if (t != null) t.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            SkyIndia.aa(g);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(SkyIndia.LINE);
            g.drawOval(6, 6, 44, 44);
            g.setColor(SkyIndia.PRIMARY);
            g.draw(new Arc2D.Double(6, 6, 44, 44, -a, 100, Arc2D.OPEN));
            g.dispose();
        }
    }

    /** Shows a modal "processing payment" dialog for ~1.8 s, then runs done on the EDT. */
    static void processing(Frame owner, Runnable done) {
        JDialog d = new JDialog(owner, "Payment", true);
        d.setUndecorated(true);
        JPanel p = SkyIndia.vbox(new Spinner(), SkyIndia.gap(14), SkyIndia.lbl("Processing payment…", SkyIndia.B, 16, SkyIndia.INK),
                SkyIndia.gap(4), SkyIndia.lbl("Please don't close this window.", SkyIndia.P, 12, SkyIndia.MUTED));
        for (Component c : p.getComponents()) if (c instanceof JComponent jc) jc.setAlignmentX(0.5f);
        p.setOpaque(true);
        p.setBackground(SkyIndia.CARD);
        p.setBorder(new javax.swing.border.CompoundBorder(new LineBorder(SkyIndia.LINE, 1, true), new EmptyBorder(26, 44, 26, 44)));
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(owner);
        javax.swing.Timer t = new javax.swing.Timer(1800, e -> { d.dispose(); SwingUtilities.invokeLater(done); });
        t.setRepeats(false);
        t.start();
        d.setVisible(true);
    }

    // =========================================================
    // INVOICE
    // =========================================================
    static Path invoice(Booking b) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".skyindia", "invoices");
        Files.createDirectories(dir);
        Path p = dir.resolve("INV-" + b.pnr + ".txt");
        double taxable = b.total / 1.05, gst = b.total - taxable;
        StringBuilder legs = new StringBuilder();
        List<Flight> fl = b.legs();
        for (int i = 0; i < fl.size(); i++) {
            Flight fx = fl.get(i);
            legs.append(fl.size() > 1 ? "Flight " + (i + 1) + "   : " : "Flight     : ").append(fx.airline()).append(" ").append(fx.no()).append("  ")
                    .append(fx.from().city()).append(" (").append(fx.from().code()).append(") -> ").append(fx.to().city()).append(" (").append(fx.to().code()).append(")  ")
                    .append(fx.dep().format(SkyIndia.SD)).append(" ").append(fx.dep().format(SkyIndia.TF)).append("\n");
        }
        FareType ft = b.fare();
        String s = "SKYINDIA - TAX INVOICE\n======================\n"
                + "Invoice no : INV-" + b.pnr + "\nPNR        : " + b.pnr + "\n"
                + legs
                + "Cabin      : " + b.cls + (ft == null ? "" : "  (" + ft.label() + " fare)") + "\nPassengers : " + String.join(", ", b.names) + "\n\n"
                + String.format(Locale.ENGLISH, "%-28s %s%n", "Taxable value", SkyIndia.inr(taxable))
                + String.format(Locale.ENGLISH, "%-28s %s%n", "CGST @2.5%", SkyIndia.inr(gst / 2))
                + String.format(Locale.ENGLISH, "%-28s %s%n", "SGST @2.5%", SkyIndia.inr(gst / 2))
                + String.format(Locale.ENGLISH, "%-28s %s%n", "Discount already applied", SkyIndia.inr(b.discount))
                + (b.pointsUsed > 0 ? String.format(Locale.ENGLISH, "%-28s %d pts%n", "  of which SkyPoints", b.pointsUsed) : "")
                + "------------------------------------------\n"
                + String.format(Locale.ENGLISH, "%-28s %s%n", "TOTAL PAID", SkyIndia.inr(b.total))
                + "\nGST split is illustrative (5% assumed on the total). Computer-generated invoice.\n";
        Files.writeString(p, s);
        return p;
    }

    // =========================================================
    // HELP & SUPPORT
    // =========================================================
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static JPanel bubble(String text, boolean mine) {
        JLabel l = SkyIndia.lbl("<html><div style='width:260px'>" + esc(text) + "</div></html>", SkyIndia.P, 13, mine ? Color.WHITE : SkyIndia.INK);
        SkyIndia.Round r = new SkyIndia.Round(14, mine ? SkyIndia.PRIMARY : SkyIndia.SOFT);
        r.setLayout(new BorderLayout());
        r.setBorder(new EmptyBorder(8, 12, 8, 12));
        r.add(l);
        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 3));
        row.setOpaque(false);
        row.add(r);
        row.setAlignmentX(0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    static String reply(String q) {
        q = q.toLowerCase();
        if (q.contains("refund") || q.contains("cancel")) return "Refunds depend on your fare: Saver up to 70%, Flex up to 90%, Premium up to 100% (48h+ before departure). Cancel from My bookings.";
        if (q.contains("change") || q.contains("reschedule") || q.contains("move")) return "Press Change on an upcoming trip in My bookings. Pick a new flight and seats; you pay the change fee and fare difference, or receive money back if the new flight is cheaper.";
        if (q.contains("point") || q.contains("redeem") || q.contains("refer")) return "1 SkyPoint is worth ₹1 off a fare (up to 30% of the amount due). Share your referral code from Rewards: your friend gets 150 points and you get 250 when they take their first trip.";
        if (q.contains("stop") || q.contains("connect") || q.contains("layover")) return "If a route has no direct flight, search shows 1-stop options with the layover time. Each flight on a connection has its own seat selection.";
        if (q.contains("bag")) return "Baggage depends on your fare: Saver 15 kg, Flex 20 kg, Premium 25 kg check-in, plus 7 kg cabin. You can add extra baggage for each traveller at checkout.";
        if (q.contains("check")) return "Web check-in opens 48 hours before departure from My bookings. The Timeline page shows the exact window.";
        if (q.contains("seat")) return "Pick seats on the seat map after choosing a flight. Extra-legroom (purple) and exit-row (orange) seats cost extra.";
        if (q.contains("pay") || q.contains("upi") || q.contains("card")) return "We accept UPI, cards, net banking and wallets. Failed payments are refunded within 5-7 days.";
        return "Thanks for reaching out! An agent will email you within 24 hours.";
    }

    static JComponent helpPage() {
        JPanel s = SkyIndia.stack();
        SkyIndia.addTo(s, SkyIndia.header("Help & support", "Answers to common questions, or message our team.", null), 16);
        String[][] faq = {
                {"How do I cancel a booking?", "Open My bookings, press Cancel on an upcoming trip and review the refund shown before confirming. The refund depends on your fare type."},
                {"Can I change my flight?", "Yes. Press Change on an upcoming trip, choose another flight on the same route and new seats. You pay the change fee plus any fare difference, or receive the difference back if the new flight is cheaper."},
                {"What do Saver, Flex and Premium include?", "Saver: lowest fare, 15 kg bag, refund up to 70%. Flex: 20 kg bag, refund up to 90%, half change fee. Premium: 25 kg bag, free meal, refund up to 100%, free changes and 50% off paid seats."},
                {"How do connecting flights work?", "When there is no direct flight, or a connection is cheaper, search shows 1-stop itineraries with the layover. You choose seats on each flight."},
                {"When does web check-in open?", "48 hours before departure. The Web check-in button on My bookings becomes active, and Timeline shows the exact window and when to reach the airport."},
                {"How do SkyPoints and referrals work?", "You earn 1 point per ₹100 paid. Spend points at checkout (1 point = ₹1, up to 30% of the amount due). Refer a friend with your code: they get 150 points and you get 250 after their first booking."},
                {"Which payment methods are supported?", "UPI, credit/debit cards, net banking and wallets."},
                {"How do promo codes work?", "Enter SKY10, WELCOME500 or INTL2000 at checkout. See Offers & policies for conditions."}};
        SkyIndia.addTo(s, SkyIndia.lbl("Frequently asked questions", SkyIndia.B, 19, SkyIndia.INK), 10);
        for (String[] qa : faq) {
            SkyIndia.Round c = new SkyIndia.Round(16, SkyIndia.CARD).shadow();
            c.setLayout(new BorderLayout(0, 6));
            c.setBorder(new EmptyBorder(12, 16, 12, 16));
            JLabel ans = SkyIndia.lbl("<html><div style='width:640px'>" + qa[1] + "</div></html>", SkyIndia.P, 13, SkyIndia.MUTED);
            ans.setVisible(false);
            SkyIndia.Btn q = new SkyIndia.Btn("+  " + qa[0], 4);
            q.setHorizontalAlignment(SwingConstants.LEFT);
            q.addActionListener(e -> {
                ans.setVisible(!ans.isVisible());
                q.setText((ans.isVisible() ? "–  " : "+  ") + qa[0]);
                c.revalidate();
                c.repaint();
            });
            c.add(q, BorderLayout.NORTH);
            c.add(ans, BorderLayout.CENTER);
            SkyIndia.addTo(s, c, 8);
        }

        SkyIndia.Round chat = new SkyIndia.Round(20, SkyIndia.CARD).shadow();
        chat.setBorder(new EmptyBorder(18, 22, 20, 22));
        chat.setLayout(new BorderLayout(0, 10));
        chat.add(SkyIndia.vbox(SkyIndia.lbl("Message support", SkyIndia.B, 17, SkyIndia.INK), SkyIndia.gap(3),
                SkyIndia.lbl("Typically replies in seconds.", SkyIndia.P, 12, SkyIndia.MUTED)), BorderLayout.NORTH);
        JPanel msgs = SkyIndia.vlist();
        msgs.add(bubble("Hi! I'm the SkyIndia support assistant. Ask me about refunds, changes, baggage, check-in, seats, points or payments.", false));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(SkyIndia.CARD);
        holder.add(msgs, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder);
        sp.setBorder(new LineBorder(SkyIndia.LINE));
        sp.setPreferredSize(new Dimension(100, 230));
        chat.add(sp, BorderLayout.CENTER);
        JTextField in = SkyIndia.field();
        SkyIndia.Btn send = new SkyIndia.Btn("Send", 0);
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);
        bar.add(in, BorderLayout.CENTER);
        bar.add(send, BorderLayout.EAST);
        chat.add(bar, BorderLayout.SOUTH);
        Runnable go = () -> {
            String text = in.getText().trim();
            if (text.isEmpty()) return;
            in.setText("");
            msgs.add(bubble(text, true));
            msgs.revalidate();
            javax.swing.Timer t = new javax.swing.Timer(800, e -> {
                msgs.add(bubble(reply(text), false));
                msgs.revalidate();
                SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum()));
            });
            t.setRepeats(false);
            t.start();
            SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum()));
        };
        send.addActionListener(e -> go.run());
        in.addActionListener(e -> go.run());
        SkyIndia.addTo(s, chat, 0);
        return SkyIndia.pageOf(s);
    }
}