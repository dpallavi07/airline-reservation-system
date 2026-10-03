import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * SKYINDIA - Flight booking desktop app (v5 UI)
 *
 * Needs Models.java, Services.java and Extras.java alongside.
 *   javac *.java
 *   java SkyIndia
 *
 * v5 adds: round-trip / multi-city, connections, change booking, per-passenger extras, paid seat tiers,
 * fare types, trip timeline, destination info, saved routes + alerts, spendable SkyPoints, referrals, reviews.
 */
public class SkyIndia extends JFrame {

    // =========================================================
    // THEME
    // =========================================================
    static Color BG = new Color(0xF4F6FB), WHITE = Color.WHITE,
            PRIMARY = new Color(0x4F46E5), PRIMARY2 = new Color(0x7C3AED), BLUE = new Color(0x0EA5E9),
            INK = new Color(0x0F172A), MUTED = new Color(0x64748B), LINE = new Color(0xE2E8F0),
            SOFT = new Color(0xEEF2FF), OK = new Color(0x10B981), DANGER = new Color(0xEF4444),
            AMBER = new Color(0xF59E0B), DARK = new Color(0x111827), SIDEBAR = new Color(0x0B1220);
    static Color CARD = Color.WHITE, CARD2 = new Color(0xF8FAFC);
    static boolean dark;

    static final Color LEGROOM = new Color(0x8B5CF6), EXITROW = new Color(0xF97316), STD_SEAT = new Color(0x3B82F6);

    static void applyTheme(boolean d) {
        dark = d;
        BG = d ? new Color(0x0B1220) : new Color(0xF4F6FB);
        CARD = d ? new Color(0x1E293B) : Color.WHITE;
        CARD2 = d ? new Color(0x172033) : new Color(0xF8FAFC);
        INK = d ? new Color(0xF1F5F9) : new Color(0x0F172A);
        MUTED = d ? new Color(0x94A3B8) : new Color(0x64748B);
        LINE = d ? new Color(0x334155) : new Color(0xE2E8F0);
        SOFT = d ? new Color(0x2A3350) : new Color(0xEEF2FF);
        UIManager.put("control", CARD);
        UIManager.put("nimbusLightBackground", CARD);
        UIManager.put("text", INK);
    }

    static final int B = Font.BOLD, P = Font.PLAIN;

    static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy");
    static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter SD = DateTimeFormatter.ofPattern("dd MMM yyyy");
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE dd MMM");
    static final DateTimeFormatter TLF = DateTimeFormatter.ofPattern("EEE dd MMM, HH:mm");

    static Font f(int style, int size) { return new Font("Segoe UI", style, size); }
    static String inr(double v) { return Pricing.inr(v); }

    static String greet() {
        int h = LocalTime.now().getHour();
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    static String initials(String n) {
        String[] p = n.trim().split("\\s+");
        String s = p[0].substring(0, 1) + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "");
        return s.toUpperCase();
    }

    // =========================================================
    // APPLICATION STATE
    // =========================================================
    /** One search leg (a journey): origin, destination and date. */
    record Seg(Airport from, Airport to, LocalDate date) {}

    User me;
    String tripType = "One-way";                 // One-way | Round trip | Multi-city
    final List<Seg> segs = new ArrayList<>();    // one entry per journey
    String cls = "Economy";
    String sort = "Cheapest";
    String filter = "All";
    int adults = 1, children = 0, infants = 0;

    final List<Option> picked = new ArrayList<>();   // chosen option per journey
    int jIdx;                                        // journey currently being chosen
    FareType fareType = FareType.SAVER;
    List<Flight> tripLegs = new ArrayList<>();       // every flight segment, in order
    List<Integer> tripJourney = new ArrayList<>();   // journey index per flight segment
    List<List<String>> tripSeats = new ArrayList<>();
    int legIdx;                                      // flight segment whose seats are being chosen

    final Set<String> chosen = new TreeSet<>();
    final Extras.Confetti confetti = new Extras.Confetti();

    final CardLayout cl = new CardLayout();
    final FadePanel cards = new FadePanel(cl);
    final Map<String, JComponent> pages = new HashMap<>();
    Btn[] navs;

    List<PaxType> paxTypes() {
        List<PaxType> l = new ArrayList<>();
        for (int i = 0; i < adults; i++) l.add(PaxType.ADULT);
        for (int i = 0; i < children; i++) l.add(PaxType.CHILD);
        for (int i = 0; i < infants; i++) l.add(PaxType.INFANT);
        return l;
    }
    int seatedCount() { return adults + children; }
    int totalPax() { return adults + children + infants; }
    String paxText() {
        return adults + " adult" + (adults > 1 ? "s" : "") + (children > 0 ? ", " + children + " child" + (children > 1 ? "ren" : "") : "")
                + (infants > 0 ? ", " + infants + " infant" + (infants > 1 ? "s" : "") : "");
    }

    // =========================================================
    // DRAWING HELPERS
    // =========================================================
    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** Draws a simple airplane silhouette pointing right (angle in radians). */
    static void plane(Graphics2D g, double cx, double cy, double s, double ang, Color c) {
        AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.rotate(ang);
        g.scale(s, s);
        g.setColor(c);
        g.fill(new Ellipse2D.Double(-1, -0.13, 2, 0.26));
        Path2D half = new Path2D.Double();
        half.moveTo(0.2, 0); half.lineTo(-0.3, -0.62); half.lineTo(-0.5, -0.62); half.lineTo(-0.2, 0); half.closePath();
        half.moveTo(-0.8, 0); half.lineTo(-1.0, -0.3); half.lineTo(-1.08, -0.3); half.lineTo(-0.95, 0); half.closePath();
        g.fill(half);
        g.scale(1, -1);
        g.fill(half);
        g.setTransform(old);
    }

    // =========================================================
    // CUSTOM COMPONENTS
    // =========================================================
    static class SkyLogo extends JPanel {
        final boolean dark;
        SkyLogo(boolean dark) {
            this.dark = dark;
            setOpaque(false);
            setPreferredSize(new Dimension(200, 56));
            setMaximumSize(new Dimension(200, 56));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setPaint(new GradientPaint(0, 6, PRIMARY, 44, 50, PRIMARY2));
            g.fillRoundRect(2, 6, 44, 44, 14, 14);
            plane(g, 24, 28, 12, -0.5, WHITE);
            g.setFont(f(B, 22));
            g.setColor(dark ? WHITE : INK);
            g.drawString("SkyIndia", 56, 30);
            g.setFont(f(P, 9));
            g.setColor(dark ? new Color(0xCBD5E1) : MUTED);
            g.drawString("FLY BEYOND BOUNDARIES", 58, 44);
            g.dispose();
        }
    }

    /** Gradient background with soft shapes (login, hero banners). */
    static class Deco extends JPanel {
        final Color a, b;
        Deco(Color a, Color b) { this.a = a; this.b = b; setOpaque(true); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            g.setPaint(new GradientPaint(0, 0, a, w, h, b));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(255, 255, 255, 12));
            g.fillOval(-120, h - 260, 420, 420);
            g.fillOval(w - 220, -140, 380, 380);
            plane(g, w * 0.80, h * 0.90, 40, -0.45, new Color(255, 255, 255, 35));
            g.dispose();
        }
    }

    static double[] bez(double u, double x0, double y0, double x1, double y1, double x2, double y2) {
        double v = 1 - u;
        return new double[]{v * v * x0 + 2 * v * u * x1 + u * u * x2, v * v * y0 + 2 * v * u * y1 + u * u * y2};
    }

    /** Clean vector illustration (no bitmap): gradient, soft circles, flight arc and plane. */
    static class HeroImagePanel extends JPanel {
        float phase;
        javax.swing.Timer timer;
        HeroImagePanel() { setOpaque(false); }
        @Override public void addNotify() {
            super.addNotify();
            timer = new javax.swing.Timer(33, e -> { phase = (phase + 0.004f) % 1f; repaint(); });
            timer.start();
        }
        @Override public void removeNotify() { if (timer != null) timer.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            g.clip(new RoundRectangle2D.Double(0, 0, w, h, 24, 24));
            g.setPaint(new GradientPaint(0, 0, new Color(0x1E40AF), w, h, new Color(0x7C3AED)));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(255, 255, 255, 22));
            g.fillOval(w - 150, -70, 240, 240);
            g.setColor(new Color(255, 255, 255, 14));
            g.fillOval(-60, h - 100, 200, 200);
            QuadCurve2D arc = new QuadCurve2D.Double(28, h - 30, w * 0.45, -h * 0.15, w - 34, h * 0.42);
            g.setColor(new Color(255, 255, 255, 120));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{1f, 8f}, 0));
            g.draw(arc);
            g.setStroke(new BasicStroke(1f));
            g.setColor(WHITE);
            g.fillOval(22, h - 36, 12, 12);
            g.fillOval(w - 40, (int) (h * 0.42) - 6, 12, 12);
            g.setColor(new Color(255, 255, 255, 70));
            g.drawOval(18, h - 40, 20, 20);
            g.drawOval(w - 44, (int) (h * 0.42) - 10, 20, 20);
            double u = phase;
            for (int k = 7; k >= 1; k--) {
                double uu = u - k * 0.018;
                if (uu < 0) continue;
                double[] pt = bez(uu, 28, h - 30, w * 0.45, -h * 0.15, w - 34, h * 0.42);
                g.setColor(new Color(255, 255, 255, 14 + (8 - k) * 6));
                g.fill(new Ellipse2D.Double(pt[0] - 3, pt[1] - 3, 6, 6));
            }
            double[] p0 = bez(u, 28, h - 30, w * 0.45, -h * 0.15, w - 34, h * 0.42);
            double[] p1 = bez(Math.min(1, u + 0.01), 28, h - 30, w * 0.45, -h * 0.15, w - 34, h * 0.42);
            double ang = Math.atan2(p1[1] - p0[1], p1[0] - p0[0]);
            plane(g, p0[0], p0[1], Math.min(w, h) * 0.12, ang, WHITE);
            g.dispose();
        }
    }

    static class Round extends JPanel {
        final int arc;
        final Color fill;
        Color grad, stroke;
        boolean shadow;
        float lt;
        javax.swing.Timer lTimer;

        Round(int arc, Color fill) { this.arc = arc; this.fill = fill; setOpaque(false); }
        Round gradient(Color c) { grad = c; return this; }
        Round shadow() { shadow = true; return this; }
        Round stroke(Color c) { stroke = c; return this; }

        /** Hover "lift": the shadow deepens and the border tints. */
        class Lift extends MouseAdapter {
            @Override public void mouseEntered(MouseEvent e) { glideLift(1f); }
            @Override public void mouseExited(MouseEvent e) { if (!contains(e.getPoint())) glideLift(0f); }
        }
        Round lift() { addMouseListener(new Lift()); return this; }
        void glideLift(float target) {
            if (lTimer != null) lTimer.stop();
            lTimer = new javax.swing.Timer(16, e -> {
                lt += target > lt ? 0.15f : -0.15f;
                if (Math.abs(target - lt) < 0.15f) { lt = target; lTimer.stop(); }
                repaint();
            });
            lTimer.start();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight(), bx = 0, bw = w, bh = h;
            if (shadow) {
                for (int i = 4; i >= 1; i--) {
                    g.setColor(new Color(15, 23, 42, 7 + (int) (lt * 8)));
                    g.fillRoundRect(2 - i / 2, 1, (w - 4) + 2 * (i / 2), (h - 5) + i + (int) (lt * 3), arc + 2, arc + 2);
                }
                bx = 2; bw = w - 4; bh = h - 5;
            }
            g.setPaint(grad != null ? new GradientPaint(bx, 0, fill, bx + bw, bh, grad) : fill);
            g.fillRoundRect(bx, 0, bw, bh, arc, arc);
            g.setColor(shadow && stroke == null ? Extras.mix(dark ? LINE : new Color(0xEDF0F6), PRIMARY, lt) : stroke == null ? fill : stroke);
            if (stroke != null || shadow) g.drawRoundRect(bx, 0, bw - 1, bh - 1, arc, arc);
            g.dispose();
        }
    }

    /** kinds: 0 primary, 1 glass (on dark), 2 soft, 3 sidebar, 4 outline, 5 danger-soft */
    static class Btn extends JButton {
        final int kind;
        boolean on;
        String ico;
        float ht;
        javax.swing.Timer hTimer;

        Btn icon(String type) {
            ico = type;
            if (!getText().isEmpty()) setBorder(new EmptyBorder(10, 46, 10, 14));
            return this;
        }
        Btn(String text, int kind) {
            super(text);
            this.kind = kind;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFont(f(B, 13));
            setForeground(kind == 2 ? PRIMARY : kind == 4 ? INK : kind == 5 ? DANGER : WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(10, 18, 10, 18));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { glide(1f); }
                @Override public void mouseExited(MouseEvent e) { glide(0f); }
            });
            if (kind == 3) {
                setHorizontalAlignment(SwingConstants.LEFT);
                setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
                setAlignmentX(0f);
                setForeground(new Color(0xCBD5E1));
            }
        }
        void glide(float target) {
            if (hTimer != null) hTimer.stop();
            hTimer = new javax.swing.Timer(16, e -> {
                ht += target > ht ? 0.18f : -0.18f;
                if (Math.abs(target - ht) < 0.18f) { ht = target; hTimer.stop(); }
                repaint();
            });
            hTimer.start();
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            boolean hover = getModel().isRollover();
            int w = getWidth(), h = getHeight();
            switch (kind) {
                case 0 -> {
                    if (!isEnabled()) g.setColor(new Color(0xCBD5E1));
                    else g.setPaint(new GradientPaint(0, 0, hover ? PRIMARY2 : PRIMARY, w, 0, hover ? PRIMARY : PRIMARY2));
                }
                case 1 -> g.setColor(new Color(255, 255, 255, hover ? 50 : 22));
                case 2 -> g.setColor(hover ? new Color(0xDDD6FE) : SOFT);
                case 4 -> g.setColor(hover ? new Color(0xF1F5F9) : CARD);
                case 5 -> g.setColor(hover ? new Color(0xFECACA) : new Color(0xFEE2E2));
                default -> g.setColor(on ? PRIMARY : hover ? new Color(255, 255, 255, 22) : new Color(0, 0, 0, 0));
            }
            g.fillRoundRect(0, 0, w, h, 12, 12);
            if (ht > 0 && isEnabled()) {
                g.setColor(kind == 0 || kind == 1 ? new Color(255, 255, 255, (int) (ht * 50)) : new Color(79, 70, 229, (int) (ht * 22)));
                g.fillRoundRect(0, 0, w, h, 12, 12);
            }
            if (kind == 4) {
                g.setColor(LINE);
                g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            }
            g.dispose();
            if (kind == 3) setForeground(on ? WHITE : new Color(0xCBD5E1));
            super.paintComponent(g0);
            if (ico != null) {
                int sz = 18;
                int ix = getText().isEmpty() ? (getWidth() - sz) / 2 : 16;
                new Ico(ico, sz, getForeground()).paintIcon(this, g0, ix, (getHeight() - sz) / 2);
            }
        }
    }

    /** Vector icons (font-independent, so they never show as empty boxes). */
    static class Ico implements Icon {
        final String t;
        final int s;
        final Color c;
        Ico(String t, int s, Color c) { this.t = t; this.s = s; this.c = c; }
        public int getIconWidth() { return s; }
        public int getIconHeight() { return s; }
        private void ln(Graphics2D g, double x1, double y1, double x2, double y2) {
            g.draw(new Line2D.Double(x1 * s, y1 * s, x2 * s, y2 * s));
        }
        public void paintIcon(Component cmp, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.translate(x, y);
            g.setColor(c);
            g.setStroke(new BasicStroke(Math.max(1.6f, s / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (t) {
                case "plane" -> plane(g, s / 2.0, s / 2.0, s * 0.42, -Math.PI / 4, c);
                case "home" -> {
                    ln(g, .1, .5, .5, .13); ln(g, .5, .13, .9, .5);
                    ln(g, .2, .45, .2, .88); ln(g, .2, .88, .8, .88); ln(g, .8, .88, .8, .45);
                    ln(g, .42, .88, .42, .62); ln(g, .58, .88, .58, .62); ln(g, .42, .62, .58, .62);
                }
                case "ticket" -> {
                    g.draw(new RoundRectangle2D.Double(.08 * s, .24 * s, .84 * s, .52 * s, .16 * s, .16 * s));
                    ln(g, .66, .3, .66, .36); ln(g, .66, .46, .66, .54); ln(g, .66, .64, .66, .7);
                    ln(g, .22, .42, .5, .42); ln(g, .22, .58, .42, .58);
                }
                case "percent" -> {
                    g.draw(new Ellipse2D.Double(.14 * s, .14 * s, .24 * s, .24 * s));
                    g.draw(new Ellipse2D.Double(.62 * s, .62 * s, .24 * s, .24 * s));
                    ln(g, .8, .2, .2, .8);
                }
                case "user" -> {
                    g.draw(new Ellipse2D.Double(.32 * s, .12 * s, .36 * s, .36 * s));
                    g.draw(new Arc2D.Double(.16 * s, .56 * s, .68 * s, .6 * s, 0, 180, Arc2D.OPEN));
                }
                case "logout" -> {
                    ln(g, .5, .15, .15, .15); ln(g, .15, .15, .15, .85); ln(g, .15, .85, .5, .85);
                    ln(g, .4, .5, .9, .5); ln(g, .9, .5, .74, .34); ln(g, .9, .5, .74, .66);
                }
                case "clock" -> {
                    g.draw(new Ellipse2D.Double(.12 * s, .12 * s, .76 * s, .76 * s));
                    ln(g, .5, .28, .5, .5); ln(g, .5, .5, .66, .6);
                }
                case "check" -> { ln(g, .2, .55, .42, .75); ln(g, .42, .75, .82, .27); }
                case "swap" -> {
                    ln(g, .12, .34, .88, .34); ln(g, .88, .34, .72, .2); ln(g, .88, .34, .72, .48);
                    ln(g, .88, .68, .12, .68); ln(g, .12, .68, .28, .54); ln(g, .12, .68, .28, .82);
                }
                case "refresh" -> {
                    g.draw(new Arc2D.Double(.16 * s, .16 * s, .68 * s, .68 * s, 40, 280, Arc2D.OPEN));
                    ln(g, .8, .22, .8, .42); ln(g, .8, .42, .6, .42);
                }
                case "rupee" -> {
                    g.setFont(f(B, (int) (s * 0.95)));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString("₹", (s - fm.stringWidth("₹")) / 2, (s + fm.getAscent() - fm.getDescent()) / 2);
                }
                default -> { }
            }
            g.dispose();
        }
    }

    static JLabel icoLabel(String type, int size, Color c) { return new JLabel(new Ico(type, size, c)); }

    static JPanel bullet(String text) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        p.add(icoLabel("check", 16, new Color(0xA5B4FC)));
        p.add(lbl(text, P, 14, WHITE));
        return p;
    }

    /** Cross-fades each page in. */
    static class FadePanel extends JPanel {
        float alpha = 1f;
        javax.swing.Timer t;
        FadePanel(LayoutManager l) { super(l); }
        void fade() {
            if (t != null) t.stop();
            alpha = 0f;
            t = new javax.swing.Timer(16, e -> {
                alpha = Math.min(1f, alpha + 0.09f);
                repaint();
                if (alpha >= 1f) t.stop();
            });
            t.start();
        }
        @Override protected void paintChildren(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            super.paintChildren(g);
            g.dispose();
        }
    }

    /** Scrolling deals ticker. */
    static class Marquee extends JComponent {
        final String text;
        float off;
        javax.swing.Timer t;
        Marquee(String text) {
            this.text = text + "          ";
            setPreferredSize(new Dimension(100, 38));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        }
        @Override public void addNotify() {
            super.addNotify();
            t = new javax.swing.Timer(25, e -> { off += 1.3f; repaint(); });
            t.start();
        }
        @Override public void removeNotify() { if (t != null) t.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            g.setColor(SOFT);
            g.fillRoundRect(0, 0, w, h, 14, 14);
            g.clip(new RoundRectangle2D.Double(0, 0, w, h, 14, 14));
            g.setFont(f(B, 12));
            g.setColor(PRIMARY);
            FontMetrics fm = g.getFontMetrics();
            int tw = Math.max(1, fm.stringWidth(text));
            for (float x = -(off % tw); x < w; x += tw) g.drawString(text, x, h / 2f + 4);
            Color clear = new Color(SOFT.getRed(), SOFT.getGreen(), SOFT.getBlue(), 0);
            g.setPaint(new GradientPaint(0, 0, SOFT, 36, 0, clear));
            g.fillRect(0, 0, 36, h);
            g.setPaint(new GradientPaint(w - 36, 0, clear, w, 0, SOFT));
            g.fillRect(w - 36, 0, 36, h);
            g.dispose();
        }
    }

    /** Animated tick used on the confirmation page. */
    static class AnimCheck extends JComponent {
        float p;
        javax.swing.Timer t;
        AnimCheck(int d) {
            Dimension s = new Dimension(d, d);
            setPreferredSize(s); setMinimumSize(s); setMaximumSize(s);
        }
        @Override public void addNotify() {
            super.addNotify();
            p = 0;
            t = new javax.swing.Timer(16, e -> { p = Math.min(1.5f, p + 0.025f); repaint(); if (p >= 1.5f) t.stop(); });
            t.start();
        }
        @Override public void removeNotify() { if (t != null) t.stop(); super.removeNotify(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int d = getWidth();
            double sc = Math.min(1, p / 0.5);
            double r = d * 0.38 * (1 - Math.pow(1 - sc, 3));
            g.setColor(new Color(255, 255, 255, 60));
            g.fill(new Ellipse2D.Double(d / 2.0 - r, d / 2.0 - r, 2 * r, 2 * r));
            if (p > 0.5f && p < 1.5f) {
                double q = (p - 0.5) / 1.0, rr = d * (0.38 + 0.12 * q);
                g.setColor(new Color(255, 255, 255, (int) (110 * (1 - q))));
                g.setStroke(new BasicStroke(2f));
                g.draw(new Ellipse2D.Double(d / 2.0 - rr, d / 2.0 - rr, 2 * rr, 2 * rr));
            }
            double cp = Math.max(0, Math.min(1, (p - 0.4) / 0.6));
            g.setColor(WHITE);
            g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double ax = .30 * d, ay = .52 * d, bx = .44 * d, by = .66 * d, cx = .70 * d, cy = .36 * d;
            double a = Math.min(1, cp * 2), b = Math.max(0, cp * 2 - 1);
            if (a > 0) g.draw(new Line2D.Double(ax, ay, ax + (bx - ax) * a, ay + (by - ay) * a));
            if (b > 0) g.draw(new Line2D.Double(bx, by, bx + (cx - bx) * b, by + (cy - by) * b));
            g.dispose();
        }
    }

    /** One seat on the map. The base colour shows the seat tier (standard / extra legroom / exit row). */
    static class SeatButton extends JButton {
        final String seatId;
        final boolean booked;
        final Color base;
        boolean selected;
        float pop;
        void pulse() {
            pop = 1f;
            javax.swing.Timer tm = new javax.swing.Timer(16, null);
            tm.addActionListener(e -> {
                pop -= 0.12f;
                if (pop <= 0) { pop = 0; tm.stop(); }
                repaint();
            });
            tm.start();
        }
        SeatButton(String seatId, boolean booked, Color base) {
            this.seatId = seatId;
            this.booked = booked;
            this.base = base;
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setPreferredSize(new Dimension(46, 44));
            if (booked) setEnabled(false);
            else setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            if (pop > 0) {
                double sc = 1 + 0.12 * pop;
                g.translate(w / 2.0, h / 2.0);
                g.scale(sc, sc);
                g.translate(-w / 2.0, -h / 2.0);
            }
            boolean hover = getModel().isRollover() && !booked;
            Color body, outline;
            if (booked) { body = new Color(0xCBD5E1); outline = new Color(0x94A3B8); }
            else if (selected) { body = new Color(0x16A34A); outline = new Color(0x15803D); }
            else { body = hover ? Extras.mix(base, Color.BLACK, 0.18f) : base; outline = Extras.mix(base, Color.BLACK, 0.35f); }
            g.setColor(new Color(15, 23, 42, 22));
            g.fillRoundRect(4, 7, w - 8, h - 8, 12, 12);
            g.setColor(body);
            g.fillRoundRect(w / 5, 2, w - 2 * (w / 5), h * 4 / 10, 8, 8);
            g.fillRoundRect(3, h * 3 / 10, w - 6, h * 7 / 10 - 3, 10, 10);
            g.setColor(outline);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(6, h * 4 / 10, 6, h - 8);
            g.drawLine(w - 6, h * 4 / 10, w - 6, h - 8);
            g.setColor(WHITE);
            int cx = w / 2, cy = h * 6 / 10 + 3;
            if (booked) {
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                g.drawLine(cx - 5, cy + 5, cx + 5, cy - 5);
            } else if (selected) {
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 6, cy, cx - 2, cy + 5);
                g.drawLine(cx - 2, cy + 5, cx + 6, cy - 5);
            } else {
                g.setFont(f(B, 9));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(seatId, (w - fm.stringWidth(seatId)) / 2, cy + 4);
            }
            g.dispose();
        }
    }

    static class LegendSeat extends JComponent {
        final Color color;
        final String symbol;
        LegendSeat(Color color, String symbol) {
            this.color = color; this.symbol = symbol;
            setPreferredSize(new Dimension(32, 28));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(color);
            g.fillRoundRect(6, 7, getWidth() - 12, getHeight() - 8, 7, 7);
            g.fillRoundRect(10, 2, getWidth() - 20, 9, 5, 5);
            g.setColor(WHITE);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            if (symbol.equals("✓")) { g.drawLine(cx - 5, 17, cx - 2, 21); g.drawLine(cx - 2, 21, cx + 5, 13); }
            else if (symbol.equals("×")) { g.drawLine(cx - 4, 13, cx + 4, 21); g.drawLine(cx - 4, 21, cx + 4, 13); }
            g.dispose();
        }
    }

    static class Avatar extends JComponent {
        final String text;
        final int d;
        Avatar(String text, int d) {
            this.text = text; this.d = d;
            Dimension s = new Dimension(d, d);
            setPreferredSize(s); setMinimumSize(s); setMaximumSize(s);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setPaint(new GradientPaint(0, 0, PRIMARY, d, d, PRIMARY2));
            g.fillOval(0, 0, d - 1, d - 1);
            g.setColor(WHITE);
            g.setFont(f(B, Math.max(10, d * 38 / 100)));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (d - fm.stringWidth(text)) / 2, (d + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    /** Dotted route line with a plane, duration above and a label below. */
    static class RouteLine extends JComponent {
        final String top, bottom;
        Color bc = OK;
        RouteLine(String top, String bottom) { this(top, bottom, 130); }
        RouteLine(String top, String bottom, int w) {
            this.top = top; this.bottom = bottom;
            setPreferredSize(new Dimension(w, 54));
            setMinimumSize(new Dimension(80, 54));
        }
        RouteLine tint(Color c) { bc = c; return this; }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), y = getHeight() / 2;
            g.setColor(new Color(0xCBD5E1));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0));
            g.drawLine(10, y, w - 10, y);
            g.setStroke(new BasicStroke(1f));
            g.fillOval(3, y - 4, 8, 8);
            g.fillOval(w - 11, y - 4, 8, 8);
            g.setColor(CARD);
            g.fillOval(w / 2 - 13, y - 11, 26, 22);
            plane(g, w / 2.0, y, 8, 0, PRIMARY);
            g.setFont(f(B, 11));
            g.setColor(INK);
            g.drawString(top, (w - g.getFontMetrics().stringWidth(top)) / 2, y - 14);
            g.setFont(f(P, 10));
            g.setColor(bc);
            g.drawString(bottom, (w - g.getFontMetrics().stringWidth(bottom)) / 2, y + 24);
            g.dispose();
        }
    }

    static class Barcode extends JComponent {
        final String seed;
        Barcode(String seed) { this.seed = seed; setPreferredSize(new Dimension(230, 54)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Random r = new Random(seed.hashCode());
            g.setColor(INK);
            int x = 0;
            while (x < getWidth() - 3) {
                int w = 1 + r.nextInt(3);
                g.fillRect(x, 0, w, getHeight());
                x += w + 1 + r.nextInt(3);
            }
            g.dispose();
        }
    }

    static class DashLine extends JComponent {
        DashLine() { setPreferredSize(new Dimension(10, 14)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 14)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(new Color(0xCBD5E1));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{6f, 5f}, 0));
            g.drawLine(0, 7, getWidth(), 7);
            g.dispose();
        }
    }

    static class Steps extends JComponent {
        static final String[] N = {"Search", "Flights", "Seats", "Details", "Confirmed"};
        final int cur;
        Steps(int cur) {
            this.cur = cur;
            setPreferredSize(new Dimension(500, 56));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int n = N.length, w = getWidth(), pad = 60, y = 16;
            for (int i = 0; i < n; i++) {
                int cx = pad + (w - 2 * pad) * i / (n - 1);
                if (i < n - 1) {
                    int nx = pad + (w - 2 * pad) * (i + 1) / (n - 1);
                    g.setColor(i < cur ? PRIMARY : LINE);
                    g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(cx + 15, y, nx - 15, y);
                }
                boolean done = i < cur, active = i == cur;
                g.setColor(done || active ? PRIMARY : CARD);
                g.fillOval(cx - 13, y - 13, 26, 26);
                if (!done && !active) {
                    g.setColor(LINE);
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(cx - 13, y - 13, 26, 26);
                }
                g.setColor(done || active ? WHITE : MUTED);
                if (done) {
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(cx - 5, y, cx - 2, y + 4);
                    g.drawLine(cx - 2, y + 4, cx + 5, y - 4);
                } else {
                    String t = String.valueOf(i + 1);
                    g.setFont(f(B, 12));
                    g.drawString(t, cx - g.getFontMetrics().stringWidth(t) / 2, y + 4);
                }
                g.setFont(f(active ? B : P, 11));
                g.setColor(i <= cur ? INK : MUTED);
                g.drawString(N[i], cx - g.getFontMetrics().stringWidth(N[i]) / 2, y + 34);
            }
            g.dispose();
        }
    }

    /** Five-star rating display (fractional values fill partially/half). */
    static class Stars extends JComponent {
        final double v;
        final int sz;
        Stars(double v, int sz) {
            this.v = v; this.sz = sz;
            Dimension d = new Dimension(sz * 5 + 8, sz + 2);
            setPreferredSize(d); setMaximumSize(d); setMinimumSize(d);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            double R = sz / 2.0, r = R * 0.45;
            for (int i = 0; i < 5; i++) {
                double cx = i * (sz + 2) + R, cy = R + 1;
                Path2D star = new Path2D.Double();
                for (int k = 0; k < 10; k++) {
                    double a = -Math.PI / 2 + k * Math.PI / 5, rad = k % 2 == 0 ? R : r;
                    double x = cx + Math.cos(a) * rad, y = cy + Math.sin(a) * rad;
                    if (k == 0) star.moveTo(x, y); else star.lineTo(x, y);
                }
                star.closePath();
                g.setColor(v >= i + 0.75 ? AMBER : v >= i + 0.25 ? Extras.mix(LINE, AMBER, 0.55f) : LINE);
                g.fill(star);
            }
            g.dispose();
        }
    }

    /** Dot + connecting line for the trip timeline. */
    static class TLDot extends JComponent {
        final Color c;
        final boolean first, last, past;
        TLDot(Color c, boolean first, boolean last, boolean past) {
            this.c = c; this.first = first; this.last = last; this.past = past;
            setPreferredSize(new Dimension(26, 10));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int cx = 13, cy = 14;
            g.setColor(LINE);
            g.setStroke(new BasicStroke(2.5f));
            if (!first) g.drawLine(cx, 0, cx, cy);
            if (!last) g.drawLine(cx, cy, cx, getHeight());
            g.setColor(past ? Extras.mix(c, LINE, 0.55f) : c);
            g.fillOval(cx - 7, cy - 7, 14, 14);
            g.setColor(CARD);
            g.fillOval(cx - 3, cy - 3, 6, 6);
            g.dispose();
        }
    }

    /** Scroll content that always matches the viewport width (no horizontal clipping). */
    static class VPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(80, r.height - 80); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static class RB extends AbstractBorder {
        final Color c;
        final int arc;
        RB(Color c, int arc) { this.c = c; this.arc = arc; }
        @Override public void paintBorder(Component cmp, Graphics g0, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(c);
            g.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(1, 1, 1, 1); }
    }

    // =========================================================
    // UI HELPERS
    // =========================================================
    static JLabel lbl(String text, int style, int size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(f(style, size));
        l.setForeground(color);
        return l;
    }

    static JPanel vbox(Component... components) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (Component c : components) {
            if (c instanceof JComponent jc) jc.setAlignmentX(0f);
            p.add(c);
        }
        return p;
    }

    static JPanel vboxR(Component... components) {
        JPanel p = vbox(components);
        for (Component c : p.getComponents()) if (c instanceof JComponent jc) jc.setAlignmentX(1f);
        return p;
    }

    static Component gap(int h) { return Box.createVerticalStrut(h); }

    static JPanel flow(int align, int hgap, Component... cs) {
        JPanel p = new JPanel(new FlowLayout(align, hgap, 0));
        p.setOpaque(false);
        for (Component c : cs) p.add(c);
        return p;
    }

    static JPanel labeled(String caption, JComponent component) {
        return vbox(lbl(caption.toUpperCase(), B, 10, MUTED), gap(5), component);
    }

    static JTextField field() {
        JTextField t = new JTextField();
        styleField(t);
        return t;
    }

    static void styleField(JTextField t) {
        t.setFont(f(P, 14));
        t.setPreferredSize(new Dimension(200, 40));
        t.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        t.setBackground(CARD);
        t.setForeground(INK);
        t.setCaretColor(INK);
        Border normal = new CompoundBorder(new RB(new Color(0xCBD5E1), 10), new EmptyBorder(0, 12, 0, 12));
        Border focus = new CompoundBorder(new RB(PRIMARY, 10), new EmptyBorder(0, 12, 0, 12));
        t.setBorder(normal);
        t.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { t.setBorder(focus); }
            @Override public void focusLost(FocusEvent e) { t.setBorder(normal); }
        });
    }

    /** Makes the text inside a JSpinner readable in both light and dark mode. */
    static void styleSpinner(JSpinner sp) {
        sp.setFont(f(P, 14));
        JTextField tf = ((JSpinner.DefaultEditor) sp.getEditor()).getTextField();
        tf.setFont(f(P, 14));
        tf.setOpaque(true);
        tf.setBackground(CARD);
        tf.setForeground(INK);
        tf.setCaretColor(INK);
        tf.setBorder(new EmptyBorder(0, 10, 0, 6));
        sp.getEditor().setBackground(CARD);
        sp.setPreferredSize(new Dimension(90, 40));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    }

    static <T> JComboBox<T> combo(T[] items, int width) {
        JComboBox<T> c = new JComboBox<>(items);
        c.setFont(f(P, 14));
        c.setPreferredSize(new Dimension(width, 40));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        // Nimbus draws a light gradient in both themes, so the shown value must stay dark.
        c.setForeground(new Color(0x0F172A));
        c.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                if (index == -1) {            // the closed box (value currently shown)
                    setOpaque(false);
                    setForeground(new Color(0x0F172A));
                } else {                      // the dropdown list follows the theme
                    setOpaque(true);
                    setBackground(sel ? PRIMARY : CARD);
                    setForeground(sel ? WHITE : INK);
                }
                return this;
            }
        });
        return c;
    }

    static JLabel pill(String text, Color color) {
        JLabel l = new JLabel(text) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                aa(g);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 30));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
                super.paintComponent(g0);
            }
        };
        l.setFont(f(B, 10));
        l.setForeground(color);
        l.setBorder(new EmptyBorder(4, 10, 4, 10));
        l.setOpaque(false);
        return l;
    }

    static JPanel vlist() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    static JPanel stack() { return vlist(); }

    /** Fix the width but keep the natural height (a fixed tiny height clips content). */
    static void fixW(JComponent c, int w) {
        c.setPreferredSize(null);
        c.setPreferredSize(new Dimension(w, c.getPreferredSize().height));
    }

    static void addTo(JPanel s, JComponent c, int gapAfter) {
        c.setAlignmentX(0f);
        s.add(c);
        if (gapAfter > 0) s.add(gap(gapAfter));
    }

    static JScrollPane vscroll(JComponent content) {
        VPanel v = new VPanel();
        v.setLayout(new BorderLayout());
        v.setBackground(BG);
        v.add(content, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(v);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        sp.getViewport().setBackground(BG);
        return sp;
    }

    /** A full scrolling page with standard padding. */
    static JComponent pageOf(JPanel stack) {
        stack.setBorder(new EmptyBorder(22, 30, 28, 30));
        return vscroll(stack);
    }

    /** A non-scrolling frame (used where side panels must stay visible). */
    static JPanel frame() {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(20, 30, 20, 30));
        return p;
    }

    static JPanel header(String title, String sub, JComponent right) {
        JPanel h = new JPanel(new BorderLayout(16, 0));
        h.setOpaque(false);
        h.add(vbox(lbl(title, B, 27, INK), gap(4), lbl(sub, P, 13, MUTED)), BorderLayout.WEST);
        if (right != null) {
            JPanel r = new JPanel(new GridBagLayout());
            r.setOpaque(false);
            r.add(right);
            h.add(r, BorderLayout.EAST);
        }
        return h;
    }

    static JPanel kv(String key, String value, boolean big) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(4, 0, 4, 0));
        p.add(lbl(key, big ? B : P, big ? 15 : 13, big ? INK : MUTED), BorderLayout.WEST);
        p.add(lbl(value, B, big ? 19 : 13, big ? PRIMARY : INK), BorderLayout.EAST);
        return p;
    }

    static JPanel detail(String title, String value) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setOpaque(false);
        p.add(lbl(title, B, 9, MUTED), BorderLayout.NORTH);
        p.add(lbl(value, B, 13, INK), BorderLayout.CENTER);
        return p;
    }

    static JLabel wrap(String text, int width, int style, int size, Color c) {
        return lbl("<html><div style='width:" + width + "px'>" + Extras.esc(text) + "</div></html>", style, size, c);
    }

    static void onChange(JTextField t, Runnable r) {
        t.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    static String dur(int minutes) { return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m"; }

    static String slot(Flight fx) {
        int h = fx.dep().getHour();
        return h < 5 || h >= 21 ? "Night" : h < 12 ? "Morning" : h < 17 ? "Afternoon" : "Evening";
    }

    static String signed(double v) { return v < 0 ? "- " + inr(-v) : inr(v); }

    static String[] dayLabels(int n) {
        String[] d = new String[n];
        for (int i = 0; i < n; i++) d[i] = LocalDate.now().plusDays(i + 1).format(DF);
        return d;
    }
    static void setDay(JComboBox<String> b, LocalDate d) {
        int i = (int) ChronoUnit.DAYS.between(LocalDate.now(), d) - 1;
        b.setSelectedIndex(Math.max(0, Math.min(b.getItemCount() - 1, i)));
    }
    static LocalDate dayOf(JComboBox<String> b) { return LocalDate.now().plusDays(b.getSelectedIndex() + 1); }

    static Color kindColor(String kind) { return kind.equals("Extra legroom") ? LEGROOM : kind.equals("Exit row") ? EXITROW : STD_SEAT; }

    void show(String name, JComponent component) {
        JComponent old = pages.put(name, component);
        if (old != null) cards.remove(old);
        cards.add(component, name);
        cl.show(cards, name);
        cards.fade();
        cards.revalidate();
        cards.repaint();
    }

    void info(String message) {
        JOptionPane.showMessageDialog(this, message, "SkyIndia", JOptionPane.WARNING_MESSAGE);
    }
    void ok(String message) {
        JOptionPane.showMessageDialog(this, message, "SkyIndia", JOptionPane.INFORMATION_MESSAGE);
    }

    void nav(int index) {
        if (navs == null) return;
        for (int i = 0; i < navs.length; i++) {
            navs[i].on = i == index;
            navs[i].repaint();
        }
    }

    // =========================================================
    // CONSTRUCTOR
    // =========================================================
    SkyIndia() {
        super("SkyIndia – Flight Booking");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Rectangle sc = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(980, sc.width), Math.min(620, sc.height)));
        setSize(Math.min(1360, sc.width), Math.min(860, sc.height));
        setLocationRelativeTo(null);
        if (sc.height < 780 || sc.width < 1280) setExtendedState(JFrame.MAXIMIZED_BOTH);
        setGlassPane(confetti);
        segs.add(new Seg(Catalog.byCode("DEL"), Catalog.byCode("BOM"), LocalDate.now().plusDays(7)));
        showLogin();
    }

    // =========================================================
    // LOGIN
    // =========================================================
    void showLogin() {
        JPanel root = new JPanel(new GridLayout(1, 2));

        Deco left = new Deco(new Color(0x0B1220), new Color(0x3730A3));
        left.setLayout(new GridBagLayout());
        HeroImagePanel visual = new HeroImagePanel();
        visual.setPreferredSize(new Dimension(380, 150));
        visual.setMaximumSize(new Dimension(380, 150));
        left.add(vbox(
                new SkyLogo(true), gap(30),
                lbl("Your journey.", B, 36, WHITE),
                lbl("Our priority.", B, 36, new Color(0xA5B4FC)), gap(12),
                lbl("Round trips, multi-city & connecting flights", P, 15, new Color(0xCBD5E1)), gap(24),
                bullet("18 airports across India & the world"), gap(9),
                bullet("Saver, Flex & Premium fares"), gap(9),
                bullet("SkyPoints you can spend + refer & earn"), gap(9),
                bullet("Instant digital boarding pass"), gap(24),
                visual));

        JPanel right = new JPanel(new GridBagLayout());
        right.setBackground(CARD);

        boolean[] registering = {false};
        JLabel title = lbl("Welcome back", B, 29, INK);
        JLabel subtitle = lbl("Sign in to manage your trips", P, 14, MUTED);
        JLabel error = lbl(" ", B, 12, DANGER);
        JTextField name = field(), phone = field(), email = field(), referral = field();
        JPasswordField password = new JPasswordField();
        styleField(password);
        password.setPreferredSize(new Dimension(340, 40));

        JPanel namePanel = labeled("Full name", name), phonePanel = labeled("Mobile (10 digits)", phone),
                refPanel = labeled("Referral code (optional)", referral);
        namePanel.setVisible(false);
        phonePanel.setVisible(false);
        refPanel.setVisible(false);

        JCheckBox showPw = new Extras.CB("Show password");
        showPw.setOpaque(false);
        showPw.setFont(f(P, 12));
        showPw.setForeground(MUTED);
        char echo = password.getEchoChar();
        showPw.addActionListener(e -> password.setEchoChar(showPw.isSelected() ? (char) 0 : echo));

        Btn action = new Btn("Sign in", 0);
        action.setFont(f(B, 14));
        action.setPreferredSize(new Dimension(340, 46));
        action.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));

        JButton toggle = new JButton("New here? Create an account");
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setFocusPainted(false);
        toggle.setFont(f(B, 13));
        toggle.setForeground(PRIMARY);
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggle.setHorizontalAlignment(SwingConstants.LEFT);

        toggle.addActionListener(e -> {
            registering[0] = !registering[0];
            namePanel.setVisible(registering[0]);
            phonePanel.setVisible(registering[0]);
            refPanel.setVisible(registering[0]);
            title.setText(registering[0] ? "Create account" : "Welcome back");
            subtitle.setText(registering[0] ? "Join SkyIndia in seconds" : "Sign in to manage your trips");
            action.setText(registering[0] ? "Create account" : "Sign in");
            toggle.setText(registering[0] ? "Already registered? Sign in" : "New here? Create an account");
            error.setText(" ");
            right.revalidate();
            right.repaint();
        });

        ActionListener submit = e -> {
            try {
                String pw = new String(password.getPassword());
                me = registering[0]
                        ? Auth.register(name.getText(), email.getText(), phone.getText(), pw, referral.getText())
                        : Auth.login(email.getText(), pw);
                showShell();
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
            }
        };
        action.addActionListener(submit);
        password.addActionListener(submit);
        email.addActionListener(e -> password.requestFocusInWindow());

        right.add(vbox(
                new SkyLogo(false), gap(24), title, gap(4), subtitle, gap(22),
                namePanel, gap(10), phonePanel, gap(10),
                labeled("Email", email), gap(10),
                labeled("Password", password), gap(4), showPw, gap(6),
                refPanel, gap(6),
                error, gap(8), action, gap(10), toggle));

        root.add(left);
        JScrollPane rs = new JScrollPane(right);
        rs.setBorder(null);
        rs.getViewport().setBackground(CARD);
        root.add(rs);
        setContentPane(root);
        revalidate();
        repaint();
    }

    // =========================================================
    // SHELL (sidebar + top bar + pages)
    // =========================================================
    void showShell() {
        Rewards.code(me);                    // make sure older accounts have a referral code
        JPanel root = new JPanel(new BorderLayout());

        Color sbc = dark ? new Color(0x070C17) : SIDEBAR;
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(sbc);
        sidebar.setPreferredSize(new Dimension(232, 10));
        sidebar.setBorder(new EmptyBorder(22, 14, 18, 14));

        JPanel topPart = vlist();
        SkyLogo logo = new SkyLogo(true);
        logo.setAlignmentX(0f);
        topPart.add(logo);
        topPart.add(gap(22));
        JLabel menu = lbl("MAIN MENU", B, 10, new Color(0x64748B));
        menu.setAlignmentX(0f);
        menu.setBorder(new EmptyBorder(0, 6, 0, 0));
        topPart.add(menu);
        topPart.add(gap(8));
        sidebar.add(topPart, BorderLayout.NORTH);

        String[] names = {"Dashboard", "Explore flights", "My bookings", "Trip timeline", "Saved routes", "Destinations",
                "Rewards & referrals", "Reviews", "Offers & policies", "My profile", "Help & support"};
        String[] icons = {"home", "plane", "ticket", "clock", "refresh", "swap", "rupee", "user", "percent", "user", "check"};
        Runnable[] go = {this::goDashboard, this::goSearch, this::goBookings, () -> goTimeline(null), this::goSaved,
                () -> goDest(null), this::goRewards, this::goReviews, this::goOffers, this::goProfile, this::goHelp};
        navs = new Btn[names.length];
        JPanel navPanel = new JPanel();
        navPanel.setLayout(new BoxLayout(navPanel, BoxLayout.Y_AXIS));
        navPanel.setBackground(sbc);
        for (int i = 0; i < names.length; i++) {
            navs[i] = new Btn(names[i], 3).icon(icons[i]);
            Runnable r = go[i];
            navs[i].addActionListener(e -> r.run());
            navPanel.add(navs[i]);
            navPanel.add(gap(3));
        }
        JScrollPane navScroll = new JScrollPane(navPanel);
        navScroll.setBorder(null);
        navScroll.getViewport().setBackground(sbc);
        navScroll.setBackground(sbc);
        navScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(navScroll, BorderLayout.CENTER);

        JPanel bottom = vlist();
        bottom.add(gap(8));
        Round help = new Round(16, new Color(255, 255, 255, 14));
        help.setLayout(new BorderLayout());
        help.setBorder(new EmptyBorder(11, 14, 11, 14));
        help.add(vbox(lbl("Need help?", B, 13, WHITE), gap(3),
                lbl("24×7 support: 1800-123-SKY", P, 11, new Color(0x94A3B8))));
        help.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        help.setAlignmentX(0f);
        bottom.add(help);
        bottom.add(gap(8));

        Btn themeBtn = new Btn(dark ? "Light mode" : "Dark mode", 1).icon("swap");
        themeBtn.setHorizontalAlignment(SwingConstants.LEFT);
        themeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        themeBtn.setAlignmentX(0f);
        themeBtn.addActionListener(e -> { applyTheme(!dark); showShell(); });
        bottom.add(themeBtn);
        bottom.add(gap(6));

        Btn logout = new Btn("Log out", 1).icon("logout");
        logout.setHorizontalAlignment(SwingConstants.LEFT);
        logout.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        logout.setAlignmentX(0f);
        logout.addActionListener(e -> {
            me = null;
            navs = null;
            filter = "All";
            showLogin();
        });
        bottom.add(logout);
        sidebar.add(bottom, BorderLayout.SOUTH);

        // top bar
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(CARD);
        top.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(9, 30, 9, 30)));
        top.add(lbl("Flight booking  •  " + LocalDate.now().format(DF), P, 12, MUTED), BorderLayout.WEST);
        JPanel who = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        who.setOpaque(false);
        who.add(Extras.bell(() -> Extras.notifications(me.email, Bookings.of(me.email))), 0);
        who.add(vboxR(lbl(me.name, B, 12, INK), lbl(me.email, P, 10, MUTED)));
        who.add(new Avatar(initials(me.name), 34));
        top.add(who, BorderLayout.EAST);

        cards.removeAll();
        pages.clear();
        cards.setBackground(BG);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(cards, BorderLayout.CENTER);

        root.add(sidebar, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
        revalidate();
        goDashboard();
    }

    // =========================================================
    // DASHBOARD
    // =========================================================
    Round metric(String icon, String title, double target, boolean money) {
        Round card = new Round(18, CARD).shadow().lift();
        card.setBorder(new EmptyBorder(16, 18, 18, 18));
        card.setLayout(new BorderLayout(12, 0));
        JPanel ic = new Round(12, SOFT);
        ic.setLayout(new GridBagLayout());
        ic.setPreferredSize(new Dimension(42, 42));
        ic.add(icoLabel(icon, 22, PRIMARY));
        JPanel wrapIc = new JPanel(new GridBagLayout());
        wrapIc.setOpaque(false);
        wrapIc.add(ic);
        card.add(wrapIc, BorderLayout.WEST);
        JLabel val = lbl(money ? inr(0) : "0", B, 19, INK);
        card.add(vbox(lbl(title, B, 10, MUTED), gap(3), val), BorderLayout.CENTER);
        int[] step = {0};
        javax.swing.Timer tm = new javax.swing.Timer(18, null);
        tm.addActionListener(e -> {
            step[0]++;
            double pr = Math.min(1, step[0] / 36.0), v = target * (1 - Math.pow(1 - pr, 3));
            val.setText(money ? inr(v) : String.valueOf(Math.round(v)));
            if (pr >= 1) tm.stop();
        });
        tm.start();
        return card;
    }

    void goDashboard() {
        nav(0);
        List<Booking> bookings = Bookings.of(me.email);
        List<Booking> upcoming = bookings.stream()
                .filter(b -> b.status().equals("Upcoming") && Bookings.nextLeg(b) != null)
                .sorted(Comparator.comparing(b -> Bookings.nextLeg(b).dep()))
                .toList();

        JPanel s = stack();

        Round hero = new Round(26, new Color(0x111827)).gradient(new Color(0x3730A3));
        hero.setBorder(new EmptyBorder(26, 30, 26, 30));
        hero.setLayout(new BorderLayout(24, 0));
        Btn explore = new Btn("Search flights  →", 0);
        explore.addActionListener(e -> goSearch());
        Btn mine = new Btn("My bookings", 1);
        mine.addActionListener(e -> goBookings());
        hero.add(vbox(
                lbl(greet().toUpperCase(), B, 11, new Color(0xA5B4FC)), gap(6),
                lbl("Welcome back, " + me.name.split(" ")[0], B, 29, WHITE), gap(5),
                lbl(upcoming.isEmpty() ? "Ready for your next adventure?" : "Your next journey is waiting.", P, 15, new Color(0xE0E7FF)),
                gap(18), flow(FlowLayout.LEFT, 10, explore, mine)), BorderLayout.CENTER);
        HeroImagePanel img = new HeroImagePanel();
        img.setPreferredSize(new Dimension(280, 140));
        hero.add(img, BorderLayout.EAST);
        addTo(s, hero, 12);
        StringBuilder deals = new StringBuilder("LIVE DEALS     ");
        for (String[] r : new String[][]{{"DEL", "BOM"}, {"BOM", "DXB"}, {"DEL", "LHR"}, {"BLR", "SIN"}, {"HYD", "BKK"}, {"DEL", "DOH"}})
            deals.append(r[0]).append(" → ").append(r[1]).append(" from ")
                    .append(inr(Catalog.minFare(Catalog.byCode(r[0]), Catalog.byCode(r[1])))).append("      •      ");
        deals.append("Use code SKY10 for 10% off");
        addTo(s, new Marquee(deals.toString()), 14);

        long trips = bookings.stream().filter(b -> !b.cancelled).count();
        double spent = bookings.stream().filter(b -> !b.cancelled).mapToDouble(b -> b.total).sum();
        double saved = bookings.stream().mapToDouble(b -> b.discount).sum();
        JPanel metrics = new JPanel(new GridLayout(1, 4, 14, 0));
        metrics.setOpaque(false);
        metrics.add(metric("plane", "TRIPS", trips, false));
        metrics.add(metric("clock", "UPCOMING", upcoming.size(), false));
        metrics.add(metric("percent", "SAVED", saved, true));
        metrics.add(metric("rupee", "TOTAL SPEND", spent, true));
        addTo(s, metrics, 16);

        addTo(s, Extras.loyalty(me, this::goRewards), 16);

        JPanel mid = new JPanel(new BorderLayout(16, 0));
        mid.setOpaque(false);

        Round trip = new Round(20, CARD).shadow();
        trip.setBorder(new EmptyBorder(20, 22, 20, 22));
        trip.setLayout(new BorderLayout(0, 14));
        Btn all = new Btn("View all  →", 2);
        all.addActionListener(e -> goBookings());
        JPanel th = new JPanel(new BorderLayout());
        th.setOpaque(false);
        th.add(vbox(lbl(upcoming.isEmpty() ? "Your next journey" : "Upcoming journey", B, 18, INK), gap(3),
                lbl(upcoming.isEmpty() ? "Booked trips will appear here." : "Your itinerary at a glance.", P, 12, MUTED)), BorderLayout.WEST);
        th.add(all, BorderLayout.EAST);
        trip.add(th, BorderLayout.NORTH);

        if (upcoming.isEmpty()) {
            JPanel empty = new JPanel(new GridBagLayout());
            empty.setOpaque(false);
            empty.setBorder(new EmptyBorder(14, 0, 14, 0));
            Btn go = new Btn("Find a flight", 0);
            go.addActionListener(e -> goSearch());
            empty.add(vbox(icoLabel("plane", 40, PRIMARY), gap(8), lbl("No upcoming trips", B, 17, INK), gap(4),
                    lbl("Search flights and start planning.", P, 12, MUTED), gap(12), go));
            trip.add(empty, BorderLayout.CENTER);
        } else {
            Booking b = upcoming.get(0);
            Flight fx = Bookings.nextLeg(b);
            int li = b.legs().indexOf(fx);
            Round it = new Round(16, CARD2).stroke(LINE);
            it.setBorder(new EmptyBorder(16, 18, 16, 18));
            it.setLayout(new BorderLayout(14, 0));
            it.add(vbox(flow(FlowLayout.LEFT, 6, pill("UPCOMING", PRIMARY), pill(b.tripType().toUpperCase(), BLUE)), gap(8),
                    lbl(fx.from().code() + "  →  " + fx.to().code(), B, 22, INK),
                    gap(3), lbl(fx.from().city() + " to " + fx.to().city(), P, 12, MUTED)), BorderLayout.WEST);
            JPanel det = new JPanel(new GridLayout(1, 3, 12, 0));
            det.setOpaque(false);
            det.add(detail("DEPARTS", fx.dep().format(TF)));
            det.add(detail("DATE", fx.dep().format(SD)));
            det.add(detail("SEATS", b.legSeats(li).isEmpty() ? "-" : String.join(", ", b.legSeats(li))));
            JPanel dw = new JPanel(new GridBagLayout());
            dw.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
            dw.add(det, gc);
            it.add(dw, BorderLayout.CENTER);
            Btn view = new Btn("View ticket", 0);
            view.addActionListener(e -> showTicket(b));
            JPanel vw = new JPanel(new GridBagLayout());
            vw.setOpaque(false);
            vw.add(view);
            it.add(vw, BorderLayout.EAST);
            it.add(Extras.statusPanel(fx), BorderLayout.SOUTH);
            trip.add(it, BorderLayout.CENTER);
            if (upcoming.size() > 1)
                trip.add(lbl("+ " + (upcoming.size() - 1) + " more upcoming trip" + (upcoming.size() > 2 ? "s" : ""), P, 12, MUTED), BorderLayout.SOUTH);
        }
        mid.add(trip, BorderLayout.CENTER);

        Round offers = new Round(20, CARD).shadow();
        offers.setPreferredSize(new Dimension(310, 10));
        offers.setBorder(new EmptyBorder(20, 22, 20, 22));
        offers.setLayout(new BorderLayout(0, 12));
        offers.add(vbox(lbl("Today's offers", B, 18, INK), gap(3), lbl("Apply at checkout", P, 12, MUTED)), BorderLayout.NORTH);
        offers.add(vbox(
                offerLine("SKY10", "10% off, up to ₹1,500"), gap(10),
                offerLine("WELCOME500", "₹500 off your first trip"), gap(10),
                offerLine("INTL2000", "₹2,000 off international ≥ ₹30,000")), BorderLayout.CENTER);
        Btn allOffers = new Btn("See all offers", 2);
        allOffers.addActionListener(e -> goOffers());
        offers.add(allOffers, BorderLayout.SOUTH);
        mid.add(offers, BorderLayout.EAST);
        addTo(s, mid, 20);

        addTo(s, lbl("Popular routes", B, 19, INK), 10);
        JPanel routeRow = new JPanel(new GridLayout(1, 4, 14, 0));
        routeRow.setOpaque(false);
        for (String[] r : new String[][]{{"DEL", "BOM"}, {"BOM", "DXB"}, {"DEL", "LHR"}, {"BLR", "SIN"}})
            routeRow.add(routeCard(Catalog.byCode(r[0]), Catalog.byCode(r[1])));
        addTo(s, routeRow, 0);

        show("dashboard", pageOf(s));
    }

    JPanel offerLine(String code, String text) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        JLabel c = pill(code, PRIMARY);
        p.add(flow(FlowLayout.LEFT, 0, c), BorderLayout.WEST);
        p.add(lbl(text, P, 12, MUTED), BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return p;
    }

    Round routeCard(Airport a, Airport b) {
        Round card = new Round(18, CARD).shadow().lift();
        card.setBorder(new EmptyBorder(16, 18, 18, 18));
        card.setLayout(new BorderLayout());
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.add(vbox(
                lbl(a.code() + "  →  " + b.code(), B, 18, INK), gap(4),
                lbl(a.city() + " to " + b.city(), P, 12, MUTED), gap(8),
                lbl("From " + inr(Catalog.minFare(a, b)), B, 13, PRIMARY)));
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                tripType = "One-way";
                segs.clear();
                segs.add(new Seg(a, b, LocalDate.now().plusDays(7)));
                startSearch();
            }
        });
        return card;
    }

    // =========================================================
    // SEARCH (one-way / round trip / multi-city)
    // =========================================================
    void setTripType(String t) {
        Seg first = segs.get(0);
        List<Seg> keep = new ArrayList<>(segs);
        tripType = t;
        segs.clear();
        switch (t) {
            case "One-way" -> segs.add(first);
            case "Round trip" -> {
                segs.add(first);
                LocalDate back = keep.size() > 1 && !keep.get(1).date().isBefore(first.date()) ? keep.get(1).date() : first.date().plusDays(7);
                segs.add(new Seg(first.to(), first.from(), back));
            }
            default -> {
                segs.addAll(keep);
                if (segs.size() < 2) segs.add(new Seg(first.to(), first.from(), first.date().plusDays(3)));
            }
        }
    }

    String validateSearch() {
        if (infants > adults) return "Each infant must travel with an adult.";
        for (int i = 0; i < segs.size(); i++) {
            Seg g = segs.get(i);
            String lab = segs.size() > 1 ? "Flight " + (i + 1) + ": " : "";
            if (g.from().equals(g.to())) return lab + "Origin and destination cannot be the same.";
            if (!g.from().india() && !g.to().india()) return lab + "At least one airport must be in India.";
            if (i > 0 && g.date().isBefore(segs.get(i - 1).date()))
                return tripType.equals("Round trip") ? "The return date cannot be before the departure date." : lab + "Dates must be in order.";
        }
        return null;
    }

    void startSearch() {
        picked.clear();
        jIdx = 0;
        goResultsAnimated();
    }

    void goSearch() {
        nav(1);
        JPanel s = stack();
        addTo(s, header("Explore flights", "One-way, round trip or multi-city — direct and 1-stop options.", pill("SMART FLIGHT SEARCH", PRIMARY)), 16);

        Round sc = new Round(24, CARD).shadow();
        sc.setBorder(new EmptyBorder(22, 26, 24, 26));
        sc.setLayout(new BorderLayout(0, 18));

        Round banner = new Round(16, new Color(0x111827)).gradient(new Color(0x312E81));
        banner.setBorder(new EmptyBorder(18, 22, 18, 22));
        banner.setLayout(new BorderLayout());
        banner.add(vbox(lbl("Where are you flying?", B, 20, WHITE), gap(3),
                lbl("Domestic & international · direct and 1-stop", P, 12, new Color(0xCBD5E1))), BorderLayout.WEST);
        banner.add(lbl("Live-style fare discovery", B, 11, new Color(0xC4B5FD)), BorderLayout.EAST);
        sc.add(banner, BorderLayout.NORTH);

        final boolean multi = tripType.equals("Multi-city"), round = tripType.equals("Round trip");
        final int rows = multi ? segs.size() : 1;
        Airport[] airports = Catalog.AIRPORTS.toArray(new Airport[0]);
        String[] days = dayLabels(120);
        List<JComboBox<Airport>> fromB = new ArrayList<>(), toB = new ArrayList<>();
        List<JComboBox<String>> dateB = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Seg g = segs.get(i);
            JComboBox<Airport> fb = combo(airports, 180), tb = combo(airports, 180);
            fb.setSelectedItem(g.from());
            tb.setSelectedItem(g.to());
            JComboBox<String> db = combo(days, 180);
            setDay(db, g.date());
            fromB.add(fb); toB.add(tb); dateB.add(db);
        }
        JComboBox<String> retB = combo(days, 180);
        setDay(retB, segs.size() > 1 ? segs.get(1).date() : segs.get(0).date().plusDays(7));
        JComboBox<String> classBox = combo(Pricing.CLASS_X.keySet().toArray(new String[0]), 160);
        classBox.setSelectedItem(cls);
        JSpinner adB = new JSpinner(new SpinnerNumberModel(adults, 1, 6, 1));
        JSpinner chB = new JSpinner(new SpinnerNumberModel(children, 0, 4, 1));
        JSpinner inB = new JSpinner(new SpinnerNumberModel(infants, 0, 6, 1));
        styleSpinner(adB); styleSpinner(chB); styleSpinner(inB);

        Runnable capture = () -> {
            List<Seg> ns = new ArrayList<>();
            for (int i = 0; i < rows; i++)
                ns.add(new Seg((Airport) fromB.get(i).getSelectedItem(), (Airport) toB.get(i).getSelectedItem(), dayOf(dateB.get(i))));
            if (round) ns.add(new Seg(ns.get(0).to(), ns.get(0).from(), dayOf(retB)));
            segs.clear();
            segs.addAll(ns);
            cls = (String) classBox.getSelectedItem();
            adults = (Integer) adB.getValue();
            children = (Integer) chB.getValue();
            infants = (Integer) inB.getValue();
        };

        // trip type chips
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        chips.setOpaque(false);
        for (String t : new String[]{"One-way", "Round trip", "Multi-city"}) {
            Btn chip = new Btn(t, t.equals(tripType) ? 0 : 4);
            chip.addActionListener(e -> { capture.run(); setTripType(t); goSearch(); });
            chips.add(chip);
        }

        JPanel body = vlist();
        body.add(chips);
        body.add(gap(14));
        if (!multi) {
            Btn swap = new Btn("", 2).icon("swap");
            swap.setPreferredSize(new Dimension(46, 40));
            swap.setBorder(new EmptyBorder(0, 0, 0, 0));
            swap.addActionListener(e -> {
                Object a = fromB.get(0).getSelectedItem();
                fromB.get(0).setSelectedItem(toB.get(0).getSelectedItem());
                toB.get(0).setSelectedItem(a);
            });
            JPanel fields = new JPanel(new GridBagLayout());
            fields.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(6, 6, 6, 6);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.anchor = GridBagConstraints.SOUTH;
            gc.gridy = 0; gc.gridx = 0; gc.weightx = 1; fields.add(labeled("From", fromB.get(0)), gc);
            gc.gridx = 1; gc.weightx = 0; fields.add(swap, gc);
            gc.gridx = 2; gc.weightx = 1; fields.add(labeled("To", toB.get(0)), gc);
            gc.gridx = 3; fields.add(labeled("Departure", dateB.get(0)), gc);
            if (round) { gc.gridx = 4; fields.add(labeled("Return", retB), gc); }
            fields.setAlignmentX(0f);
            body.add(fields);
        } else {
            for (int i = 0; i < rows; i++) {
                final int idx = i;
                JPanel r = new JPanel(new GridLayout(1, 4, 12, 0));
                r.setOpaque(false);
                r.add(labeled("Flight " + (i + 1) + " · From", fromB.get(i)));
                r.add(labeled("To", toB.get(i)));
                r.add(labeled("Date", dateB.get(i)));
                Btn rm = new Btn("Remove", 5);
                rm.setEnabled(rows > 2);
                rm.addActionListener(e -> { capture.run(); segs.remove(idx); goSearch(); });
                r.add(labeled(" ", rm));
                r.setAlignmentX(0f);
                body.add(r);
                body.add(gap(8));
            }
            Btn add = new Btn("+  Add another flight", 2);
            add.setEnabled(rows < 4);
            add.addActionListener(e -> {
                capture.run();
                Seg last = segs.get(segs.size() - 1);
                Airport nxt = last.to().code().equals("DEL") ? Catalog.byCode("BOM") : Catalog.byCode("DEL");
                segs.add(new Seg(last.to(), nxt, last.date().plusDays(3)));
                goSearch();
            });
            body.add(flow(FlowLayout.LEFT, 0, add));
        }
        body.add(gap(10));

        Btn search = new Btn("Search flights  →", 0);
        search.setFont(f(B, 14));
        search.setPreferredSize(new Dimension(180, 44));
        JPanel r2 = new JPanel(new GridLayout(1, 5, 12, 0));
        r2.setOpaque(false);
        r2.add(labeled("Class", classBox));
        r2.add(labeled("Adults (12+)", adB));
        r2.add(labeled("Children (2-11)", chB));
        r2.add(labeled("Infants (<2)", inB));
        r2.add(labeled(" ", search));
        r2.setAlignmentX(0f);
        body.add(r2);
        sc.add(body, BorderLayout.CENTER);
        sc.add(flow(FlowLayout.LEFT, 8, pill("Round trip & multi-city", OK), pill("1-stop connections", BLUE), pill("Saver · Flex · Premium", PRIMARY2)), BorderLayout.SOUTH);

        search.addActionListener(e -> {
            capture.run();
            String err = validateSearch();
            if (err != null) { info(err); return; }
            startSearch();
        });
        addTo(s, sc, 22);

        // saved route + price alert
        Round ac = new Round(20, CARD).shadow();
        ac.setBorder(new EmptyBorder(18, 22, 18, 22));
        ac.setLayout(new BorderLayout(16, 0));
        JTextField target = field();
        target.setPreferredSize(new Dimension(120, 40));
        target.setMaximumSize(new Dimension(120, 40));
        Btn saveRoute = new Btn("Save route", 0);
        Btn manage = new Btn("Manage (" + SavedRoutes.of(me.email).size() + ")", 2);
        saveRoute.addActionListener(e -> {
            Airport a = (Airport) fromB.get(0).getSelectedItem(), b = (Airport) toB.get(0).getSelectedItem();
            if (a.equals(b)) { info("Pick two different airports first."); return; }
            double t = 0;
            if (!target.getText().isBlank()) {
                try { t = Double.parseDouble(target.getText().trim().replace(",", "")); }
                catch (NumberFormatException ex) { info("Enter the target fare as a number, e.g. 4500."); return; }
            }
            SavedRoutes.save(me.email, a, b, t);
            ok(a.code() + " → " + b.code() + " saved" + (t > 0 ? " with a price alert at " + inr(t) + "." : "."));
            goSearch();
        });
        manage.addActionListener(e -> goSaved());
        ac.add(vbox(lbl("Save route & price alert", B, 17, INK), gap(3),
                lbl("Saves the first route above. Add a target fare to get a notification when it drops.", P, 12, MUTED)), BorderLayout.CENTER);
        JPanel acr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        acr.setOpaque(false);
        acr.add(lbl("Target ₹", B, 12, MUTED));
        acr.add(target);
        acr.add(saveRoute);
        acr.add(manage);
        ac.add(acr, BorderLayout.EAST);
        addTo(s, ac, 22);

        addTo(s, lbl("Popular routes", B, 19, INK), 10);
        JPanel routeRow = new JPanel(new GridLayout(1, 4, 14, 0));
        routeRow.setOpaque(false);
        for (String[] r : new String[][]{{"DEL", "BOM"}, {"BOM", "DXB"}, {"DEL", "LHR"}, {"BLR", "SIN"}}) {
            Airport a = Catalog.byCode(r[0]), b = Catalog.byCode(r[1]);
            Round card = routeCard(a, b);
            for (MouseListener ml : card.getMouseListeners())
                if (!(ml instanceof Round.Lift)) card.removeMouseListener(ml);
            card.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { fromB.get(0).setSelectedItem(a); toB.get(0).setSelectedItem(b); }
            });
            routeRow.add(card);
        }
        addTo(s, routeRow, 22);

        JPanel why = new JPanel(new GridLayout(1, 3, 14, 0));
        why.setOpaque(false);
        why.add(infoCard("percent", "Fare types", "Saver, Flex or Premium: pick the baggage and refund rules you want."));
        why.add(infoCard("refresh", "Change your flight", "Move to another flight and pay or receive the fare difference."));
        why.add(infoCard("check", "Earn & spend SkyPoints", "1 point = ₹1 off your fare. Refer friends for bonus points."));
        addTo(s, why, 0);

        show("search", pageOf(s));
    }

    Round infoCard(String icon, String title, String text) {
        Round c = new Round(18, CARD).shadow().lift();
        c.setBorder(new EmptyBorder(16, 18, 18, 18));
        c.setLayout(new BorderLayout());
        c.add(vbox(icoLabel(icon, 24, PRIMARY), gap(8), lbl(title, B, 14, INK), gap(4),
                lbl("<html><div style='width:210px'>" + text + "</div></html>", P, 12, MUTED)));
        return c;
    }

    // =========================================================
    // RESULTS (once per journey)
    // =========================================================
    String journeyTitle() {
        if (segs.size() == 1) return "Choose your flight";
        if (tripType.equals("Round trip")) return jIdx == 0 ? "Choose your outbound flight" : "Choose your return flight";
        return "Choose flight " + (jIdx + 1) + " of " + segs.size();
    }

    void goResultsAnimated() {
        nav(1);
        Seg sg = segs.get(jIdx);
        JPanel s = stack();
        addTo(s, header("Searching flights…", sg.from().city() + "  →  " + sg.to().city(), null), 6);
        addTo(s, new Steps(1), 12);
        addTo(s, new Extras.Skeleton(), 0);
        show("loading", pageOf(s));
        javax.swing.Timer t = new javax.swing.Timer(900, e -> goResults());
        t.setRepeats(false);
        t.start();
    }

    void goResults() {
        nav(1);
        Seg sg = segs.get(jIdx);
        Airport A = sg.from(), Z = sg.to();
        LocalDate day = sg.date();
        double mult = Pricing.CLASS_X.get(cls);

        LocalDateTime notBefore = jIdx > 0 && picked.size() >= jIdx ? picked.get(jIdx - 1).arr().plusHours(2) : null;
        List<Option> all = new ArrayList<>();
        for (Option o : Catalog.options(A, Z, day)) if (notBefore == null || o.dep().isAfter(notBefore)) all.add(o);
        double cheapest = all.stream().mapToDouble(Option::fare).min().orElse(0);
        int fastest = all.stream().mapToInt(Option::mins).min().orElse(0);

        JPanel s = stack();

        Btn back = new Btn("← Back", 4);
        back.addActionListener(e -> {
            if (jIdx > 0) { jIdx--; while (picked.size() > jIdx) picked.remove(picked.size() - 1); goResults(); }
            else goSearch();
        });
        Btn saveR = new Btn("Save route", 2);
        saveR.addActionListener(e -> {
            String in = JOptionPane.showInputDialog(this, "Alert me when the cheapest fare falls to (₹). Enter 0 for no alert:", "0");
            if (in == null) return;
            try {
                double t = Double.parseDouble(in.trim().replace(",", ""));
                SavedRoutes.save(me.email, A, Z, t);
                ok("Saved " + A.code() + " → " + Z.code() + (t > 0 ? " with an alert at " + inr(t) : "") + ".");
            } catch (NumberFormatException ex) { info("Please enter a number."); }
        });
        Btn dest = new Btn("About " + Z.city(), 2);
        dest.addActionListener(e -> goDest(Z));
        addTo(s, header(journeyTitle(),
                A.city() + "  →  " + Z.city() + "  •  " + day.format(DF) + "  •  " + paxText() + "  •  " + cls,
                flow(FlowLayout.RIGHT, 8, dest, saveR, back)), 6);
        addTo(s, new Steps(1), 6);

        // earlier choices
        for (int j = 0; j < jIdx && j < picked.size(); j++) {
            Option o = picked.get(j);
            Seg g = segs.get(j);
            Round done = new Round(14, CARD2).stroke(LINE);
            done.setBorder(new EmptyBorder(10, 16, 10, 16));
            done.setLayout(new BorderLayout());
            done.add(lbl("Selected: " + g.from().code() + " → " + g.to().code() + "  ·  " + o.dep().format(SHORT) + "  " + o.dep().format(TF)
                    + " → " + o.arr().format(TF) + "  ·  " + o.numbers() + (o.stops() > 0 ? "  ·  1 stop via " + o.via() : "")
                    + "  ·  " + inr(o.fare() * mult) + " / traveller", B, 12, OK));
            addTo(s, done, 6);
        }

        // 7-day fare strip
        JPanel strip = new JPanel(new GridLayout(1, 7, 8, 0));
        strip.setOpaque(false);
        LocalDate minDay = LocalDate.now().plusDays(1);
        if (jIdx > 0 && segs.get(jIdx - 1).date().isAfter(minDay)) minDay = segs.get(jIdx - 1).date();
        LocalDate start = day.minusDays(3);
        if (start.isBefore(minDay)) start = minDay;
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            double min = Catalog.options(A, Z, d).stream().mapToDouble(Option::fare).min().orElse(0) * mult;
            Btn chip = new Btn("<html><center>" + d.format(SHORT) + "<br><b>" + (min > 0 ? inr(min) : "—") + "</b></center></html>", d.equals(day) ? 0 : 4);
            chip.setFont(f(P, 11));
            chip.setBorder(new EmptyBorder(8, 4, 8, 4));
            chip.addActionListener(e -> { segs.set(jIdx, new Seg(A, Z, d)); goResults(); });
            strip.add(chip);
        }
        addTo(s, strip, 16);
        addTo(s, Extras.trend(A, Z, mult), 16);

        // filter state
        Set<String> offAir = new HashSet<>();
        Set<String> timeOn = new HashSet<>();
        Set<Integer> stopsOn = new HashSet<>();
        int minP = (int) (Math.floor(all.stream().mapToDouble(o -> o.fare() * mult).min().orElse(0) / 100) * 100);
        int maxP = (int) (Math.ceil(all.stream().mapToDouble(o -> o.fare() * mult).max().orElse(100) / 100) * 100);
        int[] limit = {Math.max(maxP, minP + 100)};

        JPanel list = vlist();
        JLabel count = lbl(" ", B, 13, INK);
        Runnable[] refill = {null};

        Round fp = new Round(18, CARD).shadow();
        fp.setBorder(new EmptyBorder(18, 18, 18, 18));
        fp.setLayout(new BorderLayout());
        JPanel fb = vlist();
        fb.add(lbl("Filters", B, 16, INK));
        fb.add(gap(14));
        fb.add(lbl("STOPS", B, 10, MUTED));
        fb.add(gap(6));
        for (int st = 0; st <= 1; st++) {
            final int stv = st;
            JCheckBox cb = new Extras.CB(st == 0 ? "Non-stop" : "1 stop");
            cb.setOpaque(false);
            cb.setFont(f(P, 13));
            cb.setAlignmentX(0f);
            cb.addActionListener(e -> { if (cb.isSelected()) stopsOn.add(stv); else stopsOn.remove(stv); refill[0].run(); });
            fb.add(cb);
        }
        fb.add(gap(12));
        fb.add(lbl("AIRLINES", B, 10, MUTED));
        fb.add(gap(6));
        for (String al : new LinkedHashSet<>(all.stream().map(Option::airlines).toList())) {
            JCheckBox cb = new Extras.CB(al, true);
            cb.setOpaque(false);
            cb.setFont(f(P, 12));
            cb.setAlignmentX(0f);
            cb.addActionListener(e -> { if (cb.isSelected()) offAir.remove(al); else offAir.add(al); refill[0].run(); });
            fb.add(cb);
        }
        fb.add(gap(12));
        fb.add(lbl("DEPARTURE TIME", B, 10, MUTED));
        fb.add(gap(6));
        for (String t : new String[]{"Morning", "Afternoon", "Evening", "Night"}) {
            JCheckBox cb = new Extras.CB(t);
            cb.setOpaque(false);
            cb.setFont(f(P, 13));
            cb.setAlignmentX(0f);
            cb.addActionListener(e -> { if (cb.isSelected()) timeOn.add(t); else timeOn.remove(t); refill[0].run(); });
            fb.add(cb);
        }
        fb.add(gap(12));
        fb.add(lbl("MAX PRICE", B, 10, MUTED));
        fb.add(gap(4));
        JLabel priceLbl = lbl("Up to " + inr(limit[0]), B, 13, PRIMARY);
        priceLbl.setAlignmentX(0f);
        fb.add(priceLbl);
        JSlider slider = new JSlider(minP, limit[0], limit[0]);
        slider.setOpaque(false);
        slider.setAlignmentX(0f);
        slider.addChangeListener(e -> {
            limit[0] = slider.getValue();
            priceLbl.setText("Up to " + inr(limit[0]));
            refill[0].run();
        });
        fb.add(slider);
        fp.add(fb, BorderLayout.NORTH);
        fixW(fp, 240);

        JComboBox<String> sorting = combo(new String[]{"Cheapest", "Fastest", "Earliest"}, 130);
        sorting.setSelectedItem(sort);
        sorting.addActionListener(e -> { sort = (String) sorting.getSelectedItem(); refill[0].run(); });
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(0, 0, 10, 0));
        bar.add(count, BorderLayout.WEST);
        bar.add(flow(FlowLayout.RIGHT, 8, lbl("Sort by", P, 12, MUTED), sorting), BorderLayout.EAST);

        refill[0] = () -> {
            List<Option> l = new ArrayList<>();
            for (Option o : all) {
                if (offAir.contains(o.airlines())) continue;
                if (!stopsOn.isEmpty() && !stopsOn.contains(o.stops())) continue;
                if (!timeOn.isEmpty() && !timeOn.contains(slot(o.first()))) continue;
                if (o.fare() * mult > limit[0]) continue;
                l.add(o);
            }
            Comparator<Option> cmp = switch (sort) {
                case "Fastest" -> Comparator.comparingInt(Option::mins);
                case "Earliest" -> Comparator.comparing(Option::dep);
                default -> Comparator.comparingDouble(Option::fare);
            };
            l.sort(cmp);
            count.setText(l.size() + " option" + (l.size() == 1 ? "" : "s") + " found");
            list.removeAll();
            if (l.isEmpty()) {
                Round e = new Round(18, CARD).shadow();
                e.setBorder(new EmptyBorder(30, 30, 30, 30));
                e.setLayout(new BorderLayout());
                e.setAlignmentX(0f);
                e.add(vbox(lbl("No flights match", B, 17, INK), gap(4),
                        lbl(all.isEmpty() ? "Nothing is bookable on this date. Try another day from the strip above."
                                : "Try clearing a filter or raising the price limit.", P, 13, MUTED)));
                list.add(e);
            }
            int idx = 0;
            for (Option o : l) {
                list.add(new Extras.Reveal(optionCard(o, cheapest, fastest, mult), idx++));
                list.add(gap(10));
            }
            list.revalidate();
            list.repaint();
        };
        refill[0].run();

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(bar, BorderLayout.NORTH);
        right.add(list, BorderLayout.CENTER);

        JPanel fwrap = new JPanel(new BorderLayout());
        fwrap.setOpaque(false);
        fwrap.add(fp, BorderLayout.NORTH);

        JPanel main = new JPanel(new BorderLayout(18, 0));
        main.setOpaque(false);
        main.add(fwrap, BorderLayout.WEST);
        main.add(right, BorderLayout.CENTER);
        addTo(s, main, 0);

        show("results", pageOf(s));
    }

    JPanel optionCard(Option o, double cheapest, int fastest, double mult) {
        Round card = new Round(18, CARD).shadow().lift();
        card.setLayout(new BorderLayout(16, 6));
        card.setBorder(new EmptyBorder(16, 20, 14, 20));
        card.setAlignmentX(0f);

        int seatsLeft = Integer.MAX_VALUE;
        for (Flight fl : o.segs()) seatsLeft = Math.min(seatsLeft, Bookings.ROWS * 6 - Bookings.taken(fl).size());
        JPanel badges = flow(FlowLayout.LEFT, 5);
        ((FlowLayout) badges.getLayout()).setHgap(0);
        if (o.intl()) badges.add(pill("INTL", PRIMARY));
        if (o.stops() > 0) badges.add(pill("1 STOP", AMBER));
        if (o.fare() == cheapest) badges.add(pill("LOWEST FARE", OK));
        if (o.mins() == fastest) badges.add(pill("FASTEST", BLUE));
        if (badges.getComponentCount() == 0) badges.add(Box.createVerticalStrut(18));

        String first = o.first().airline();
        String rate = Reviews.count(first) > 0
                ? String.format(Locale.ENGLISH, "%.1f rating (%d reviews)", Reviews.avg(first), Reviews.count(first)) : "";

        JPanel id = new JPanel(new BorderLayout(12, 0));
        id.setOpaque(false);
        id.add(new Avatar(o.first().no().substring(0, 2), 42), BorderLayout.WEST);
        id.add(vbox(lbl(o.airlines(), B, o.stops() > 0 ? 13 : 15, INK), gap(2), lbl(o.numbers(), P, 12, MUTED)), BorderLayout.CENTER);
        JPanel left = vbox(id, gap(8), badges, gap(5),
                lbl(seatsLeft < 20 ? "Only " + seatsLeft + " seats left" : seatsLeft + " seats available", P, 11, seatsLeft < 20 ? DANGER : OK));
        if (!rate.isEmpty()) { left.add(gap(2)); left.add(lbl(rate, P, 11, MUTED)); }
        fixW(left, 240);
        card.add(left, BorderLayout.WEST);

        long dayShift = ChronoUnit.DAYS.between(o.dep().toLocalDate(), o.arr().toLocalDate());
        String arrTxt = o.arr().format(TF) + (dayShift > 0 ? " +" + dayShift + "d" : "");
        String sub = o.stops() == 0 ? "Non-stop" : "1 stop · " + o.via() + " · " + dur(o.layover()) + " layover";
        JPanel times = new JPanel(new BorderLayout(8, 0));
        times.setOpaque(false);
        times.add(vbox(lbl(o.dep().format(TF), B, 22, INK), lbl(o.first().from().code(), B, 12, PRIMARY)), BorderLayout.WEST);
        times.add(new RouteLine(dur(o.mins()), sub, 210).tint(o.stops() == 0 ? OK : AMBER), BorderLayout.CENTER);
        times.add(vboxR(lbl(arrTxt, B, 22, INK), lbl(o.last().to().code(), B, 12, PRIMARY)), BorderLayout.EAST);
        JPanel mid = new JPanel(new GridBagLayout());
        mid.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        mid.add(times, gc);
        card.add(mid, BorderLayout.CENTER);

        Runnable pick = () -> {
            while (picked.size() > jIdx) picked.remove(picked.size() - 1);
            picked.add(o);
            if (jIdx < segs.size() - 1) { jIdx++; goResults(); }
            else goFare();
        };
        Btn select = new Btn("Select", 0);
        select.addActionListener(e -> pick.run());
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { pick.run(); }
        });
        JPanel east = vboxR(lbl(inr(o.fare() * mult), B, 22, PRIMARY), lbl("per traveller · Saver", P, 11, MUTED), gap(8), select);
        fixW(east, 160);
        JPanel eastWrap = new JPanel(new GridBagLayout());
        eastWrap.setOpaque(false);
        eastWrap.add(east);
        card.add(eastWrap, BorderLayout.EAST);

        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(8, 0, 0, 0)));
        String footTxt;
        if (o.stops() == 0) footTxt = "Refund up to 70–100% by fare  ·  15–25 kg check-in  ·  7 kg cabin  ·  choose Saver, Flex or Premium next";
        else {
            Flight a = o.segs().get(0), b = o.segs().get(1);
            footTxt = a.no() + "  " + a.from().code() + " " + a.dep().format(TF) + " → " + a.to().code() + " " + a.arr().format(TF)
                    + "     |  " + dur(o.layover()) + " layover in " + a.to().city() + "  |     "
                    + b.no() + "  " + b.from().code() + " " + b.dep().format(TF) + " → " + b.to().code() + " " + b.arr().format(TF);
        }
        foot.add(lbl(footTxt, P, 11, MUTED));
        card.add(foot, BorderLayout.SOUTH);
        return card;
    }

    // =========================================================
    // FARE TYPE
    // =========================================================
    void buildTrip() {
        tripLegs = new ArrayList<>();
        tripJourney = new ArrayList<>();
        for (int j = 0; j < picked.size(); j++)
            for (Flight fl : picked.get(j).segs()) { tripLegs.add(fl); tripJourney.add(j); }
        tripSeats = new ArrayList<>();
        for (int i = 0; i < tripLegs.size(); i++) tripSeats.add(new ArrayList<>());
    }

    void goFare() {
        nav(1);
        buildTrip();
        List<PaxType> types = paxTypes();
        int n = types.size();

        JPanel s = stack();
        Btn back = new Btn("←  Back to flights", 4);
        back.addActionListener(e -> { jIdx = segs.size() - 1; while (picked.size() > jIdx) picked.remove(picked.size() - 1); goResults(); });
        addTo(s, header("Choose your fare", tripType + "  •  " + paxText() + "  •  " + cls, back), 6);
        addTo(s, new Steps(1), 8);

        Round sum = new Round(18, CARD).shadow();
        sum.setBorder(new EmptyBorder(16, 22, 16, 22));
        sum.setLayout(new BorderLayout());
        JPanel sl = vlist();
        sl.add(lbl("YOUR FLIGHTS", B, 10, MUTED));
        sl.add(gap(6));
        for (int j = 0; j < picked.size(); j++) {
            Option o = picked.get(j);
            Seg g = segs.get(j);
            sl.add(lbl(g.from().code() + " → " + g.to().code() + "   ·   " + o.dep().format(SHORT) + "  " + o.dep().format(TF) + " → " + o.arr().format(TF)
                    + "   ·   " + o.airlines() + " " + o.numbers() + (o.stops() > 0 ? "   ·   1 stop via " + o.via() + " (" + dur(o.layover()) + ")" : "   ·   Non-stop"), B, 13, INK));
            sl.add(gap(4));
        }
        sum.add(sl);
        addTo(s, sum, 16);

        JPanel row = new JPanel(new GridLayout(1, 3, 14, 0));
        row.setOpaque(false);
        for (FareType ft : FareType.values()) {
            Quote q = Pricing.quoteTrip(tripLegs, cls, types, null, Pricing.fill(n, 0), Pricing.fill(n, 0), false, "", me.email, true, ft, false);
            Round c = new Round(22, CARD).shadow().lift();
            if (ft == fareType) c.stroke(PRIMARY);
            c.setBorder(new EmptyBorder(20, 22, 20, 22));
            c.setLayout(new BorderLayout(0, 12));
            JPanel top = vlist();
            top.add(flow(FlowLayout.LEFT, 8, lbl(ft.label(), B, 22, INK), pill(ft.tag().toUpperCase(), ft == FareType.PREMIUM ? AMBER : ft == FareType.FLEX ? BLUE : OK)));
            top.add(gap(8));
            top.add(lbl(inr(q.total() + q.seatFees()), B, 26, PRIMARY));
            top.add(gap(2));
            top.add(lbl("total for " + n + " traveller" + (n > 1 ? "s" : "") + " · " + ft.priceNote(), P, 11, MUTED));
            top.add(gap(12));
            for (String perk : ft.perks()) {
                JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                pr.setOpaque(false);
                pr.add(icoLabel("check", 14, OK));
                pr.add(lbl(perk, P, 12, INK));
                pr.setAlignmentX(0f);
                top.add(pr);
                top.add(gap(3));
            }
            double[] r = ft.refundTiers();
            top.add(gap(6));
            top.add(wrap("Refund: 48h+ " + Math.round(r[0] * 100) + "%  ·  24–48h " + Math.round(r[1] * 100) + "%  ·  4–24h "
                    + Math.round(r[2] * 100) + "%  ·  under 4h " + Math.round(r[3] * 100) + "%", 230, P, 11, MUTED));
            c.add(top, BorderLayout.CENTER);
            Btn pickB = new Btn(ft == fareType ? "Continue with " + ft.label() + "  →" : "Choose " + ft.label(), ft == fareType ? 0 : 2);
            pickB.addActionListener(e -> { fareType = ft; legIdx = 0; goSeats(); });
            c.add(pickB, BorderLayout.SOUTH);
            row.add(c);
        }
        addTo(s, row, 10);
        addTo(s, lbl("Totals include taxes, airport fees and your member discount. Baggage, meals and seats are added in the next steps.", P, 12, MUTED), 0);
        show("fare", pageOf(s));
    }

    // =========================================================
    // SEAT MAP (one page per flight segment, paid seat tiers)
    // =========================================================
    JLabel selectedLabel, totalLabel;
    Btn continueButton;

    /**
     * Builds the cabin layout for one flight. sel is the live selection set, mine = seats that belong to this booking
     * already (shown as available when changing a flight). Seat colours: blue standard, purple extra legroom, orange exit row.
     */
    JPanel seatGrid(Flight fl, Set<String> sel, int count, Set<String> mine, String cabin, FareType ft, Runnable changed) {
        Set<String> taken = Bookings.taken(fl);
        Map<String, SeatButton> btns = new HashMap<>();
        JPanel map = vlist();
        map.setBorder(new EmptyBorder(10, 0, 0, 0));
        Round nose = new Round(40, SOFT);
        nose.setLayout(new GridBagLayout());
        nose.add(lbl("FLIGHT DECK", B, 11, PRIMARY));
        nose.setPreferredSize(new Dimension(300, 44));
        nose.setMaximumSize(new Dimension(300, 44));
        nose.setAlignmentX(0.5f);
        map.add(nose);
        map.add(gap(12));

        JPanel hdr = new JPanel(new GridLayout(1, 8, 6, 0));
        hdr.setOpaque(false);
        hdr.add(new JLabel(""));
        for (String c : new String[]{"A", "B", "C", "", "D", "E", "F"}) {
            JLabel h = lbl(c, B, 11, MUTED);
            h.setHorizontalAlignment(SwingConstants.CENTER);
            hdr.add(h);
        }
        map.add(hdr);
        map.add(gap(4));

        for (int row = 1; row <= Bookings.ROWS; row++) {
            JPanel sr = new JPanel(new GridLayout(1, 8, 6, 0));
            sr.setOpaque(false);
            String rk = Seats.kind(row + "A");
            JLabel num = lbl(String.valueOf(row), B, 10, rk.equals("Standard") ? MUTED : kindColor(rk));
            num.setHorizontalAlignment(SwingConstants.CENTER);
            sr.add(num);
            for (String col : new String[]{"A", "B", "C", "-", "D", "E", "F"}) {
                if (col.equals("-")) {
                    JLabel dot = lbl(" ", B, 14, new Color(0xCBD5E1));
                    dot.setHorizontalAlignment(SwingConstants.CENTER);
                    sr.add(dot);
                    continue;
                }
                String sid = row + col;
                boolean booked = taken.contains(sid) && !mine.contains(sid);
                SeatButton seat = new SeatButton(sid, booked, kindColor(Seats.kind(sid)));
                seat.selected = sel.contains(sid);
                double fee = Pricing.seatFee(fl, sid, cabin, ft);
                String tip = booked ? sid + " • Occupied" : Seats.label(sid) + (fee > 0 ? " • +" + inr(fee) : "");
                seat.setToolTipText(tip);
                btns.put(sid, seat);
                seat.addActionListener(e -> {
                    if (seat.selected) {
                        seat.selected = false;
                        sel.remove(sid);
                    } else {
                        if (sel.size() >= count) {
                            if (count == 1) {
                                String prev = sel.iterator().next();
                                SeatButton ps = btns.get(prev);
                                ps.selected = false;
                                ps.repaint();
                                sel.clear();
                            } else {
                                Toolkit.getDefaultToolkit().beep();
                                info("You can select only " + count + " seats. Deselect one to change.");
                                return;
                            }
                        }
                        seat.selected = true;
                        sel.add(sid);
                        seat.pulse();
                    }
                    seat.repaint();
                    changed.run();
                });
                sr.add(seat);
            }
            sr.setAlignmentX(0f);
            map.add(sr);
            map.add(gap(5));
        }
        return map;
    }

    JPanel seatLegend(Flight fl, String cabin, FareType ft) {
        double lf = Pricing.seatFee(fl, "1A", cabin, ft), ef = Pricing.seatFee(fl, "8A", cabin, ft);
        Round legend = new Round(14, CARD).shadow();
        legend.setBorder(new EmptyBorder(8, 16, 10, 16));
        legend.setLayout(new BorderLayout());
        legend.add(flow(FlowLayout.CENTER, 18,
                flow(FlowLayout.LEFT, 6, new LegendSeat(STD_SEAT, ""), lbl("Standard (free)", B, 11, INK)),
                flow(FlowLayout.LEFT, 6, new LegendSeat(LEGROOM, ""), lbl("Extra legroom " + (lf > 0 ? "+" + inr(lf) : "(included)"), B, 11, INK)),
                flow(FlowLayout.LEFT, 6, new LegendSeat(EXITROW, ""), lbl("Exit row " + (ef > 0 ? "+" + inr(ef) : "(included)"), B, 11, INK)),
                flow(FlowLayout.LEFT, 6, new LegendSeat(new Color(0x16A34A), "✓"), lbl("Selected", B, 11, INK)),
                flow(FlowLayout.LEFT, 6, new LegendSeat(new Color(0xCBD5E1), "×"), lbl("Unavailable", B, 11, INK))));
        return legend;
    }

    void goSeats() {
        nav(1);
        Flight fl = tripLegs.get(legIdx);
        int need = seatedCount();
        chosen.clear();
        chosen.addAll(tripSeats.get(legIdx));
        boolean lastLeg = legIdx == tripLegs.size() - 1;

        JPanel p = frame();
        Btn back = new Btn("←  Back", 4);
        back.addActionListener(e -> {
            if (legIdx > 0) { tripSeats.set(legIdx, new ArrayList<>(chosen)); legIdx--; goSeats(); }
            else goFare();
        });
        JPanel top = vlist();
        addTo(top, header("Choose your seats",
                "Flight " + (legIdx + 1) + " of " + tripLegs.size() + "  ·  " + fl.from().code() + " → " + fl.to().code()
                        + "  ·  select " + need + " seat" + (need > 1 ? "s" : "") + (infants > 0 ? " (infants travel on a lap)" : ""),
                flow(FlowLayout.RIGHT, 8, pill(fl.airline() + "  •  " + fl.no(), PRIMARY), back)), 2);
        addTo(top, new Steps(2), 0);
        p.add(top, BorderLayout.NORTH);

        Round cabin = new Round(28, CARD).shadow();
        cabin.setBorder(new EmptyBorder(16, 24, 18, 24));
        cabin.setLayout(new BorderLayout());
        cabin.add(vbox(lbl("AIRCRAFT CABIN", B, 10, MUTED), gap(2), lbl(cls.toUpperCase() + "  ·  " + fareType.label().toUpperCase() + " FARE", B, 14, PRIMARY)), BorderLayout.NORTH);
        cabin.add(seatGrid(fl, chosen, need, new HashSet<>(), cls, fareType, this::refreshSeats), BorderLayout.CENTER);
        JPanel cabinWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        cabinWrap.setOpaque(false);
        cabinWrap.add(cabin);

        JPanel centre = new JPanel(new BorderLayout(0, 12));
        centre.setOpaque(false);
        centre.add(seatLegend(fl, cls, fareType), BorderLayout.NORTH);
        centre.add(vscroll(cabinWrap), BorderLayout.CENTER);
        p.add(centre, BorderLayout.CENTER);

        Round sum = new Round(20, CARD).shadow();
        sum.setPreferredSize(new Dimension(290, 10));
        sum.setBorder(new EmptyBorder(20, 20, 20, 20));
        sum.setLayout(new BorderLayout(0, 14));
        selectedLabel = lbl(" ", P, 13, INK);
        totalLabel = lbl(" ", B, 22, PRIMARY);
        continueButton = new Btn(lastLeg ? "Continue  →" : "Next flight  →", 0);
        continueButton.setFont(f(B, 14));
        continueButton.setPreferredSize(new Dimension(200, 46));
        continueButton.addActionListener(e -> {
            tripSeats.set(legIdx, new ArrayList<>(chosen));
            if (lastLeg) goCheckout();
            else { legIdx++; goSeats(); }
        });
        sum.add(vbox(
                lbl("FLIGHT " + (legIdx + 1) + " OF " + tripLegs.size(), B, 10, MUTED), gap(4),
                lbl(fl.from().code() + "  →  " + fl.to().code(), B, 20, INK), gap(2),
                lbl(fl.dep().format(DF) + " · " + fl.dep().format(TF), P, 12, MUTED), gap(18),
                lbl("SEATS", B, 10, MUTED), gap(6), selectedLabel, gap(18),
                lbl("SEAT SURCHARGES (THIS FLIGHT)", B, 10, MUTED), gap(4), totalLabel,
                gap(3), lbl("Standard seats are free. Baggage, meals and taxes on next step.", P, 11, MUTED)), BorderLayout.CENTER);
        sum.add(continueButton, BorderLayout.SOUTH);
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.setBorder(new EmptyBorder(0, 18, 0, 0));
        east.add(sum, BorderLayout.CENTER);
        p.add(east, BorderLayout.EAST);

        refreshSeats();
        show("seats", p);
    }

    void refreshSeats() {
        int need = seatedCount();
        Flight fl = tripLegs.get(legIdx);
        if (chosen.isEmpty()) {
            selectedLabel.setText("<html><span style='color:#64748B'>None selected yet — pick " + need + " seat" + (need > 1 ? "s" : "") + ".</span></html>");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String sid : chosen) sb.append(Extras.esc(Seats.label(sid))).append("<br>");
            selectedLabel.setText("<html><b>" + sb + "</b><span style='color:#64748B'>" + chosen.size() + " of " + need + " selected</span></html>");
        }
        double fees = 0;
        for (String sid : chosen) fees += Pricing.seatFee(fl, sid, cls, fareType);
        totalLabel.setText(fees == 0 ? "₹0" : inr(fees));
        continueButton.setEnabled(chosen.size() == need);
    }

    // =========================================================
    // CHECKOUT
    // =========================================================
    void goCheckout() {
        nav(1);
        List<PaxType> types = paxTypes();
        int n = types.size();
        boolean freeMeal = fareType.freeMeal();

        JPanel p = frame();
        Btn back = new Btn("←  Back to seats", 4);
        back.addActionListener(e -> { legIdx = tripLegs.size() - 1; goSeats(); });
        JPanel top = vlist();
        addTo(top, header("Traveller details", "Passenger information, extras for each traveller and payment.",
                flow(FlowLayout.RIGHT, 10, lbl("Secure checkout", B, 12, OK), back)), 2);
        addTo(top, new Steps(3), 0);
        p.add(top, BorderLayout.NORTH);

        List<JTextField> names = new ArrayList<>(), ages = new ArrayList<>();
        List<JComboBox<String>> bagBoxes = new ArrayList<>(), mealBoxes = new ArrayList<>();
        JPanel form = vlist();
        form.setBorder(new EmptyBorder(2, 2, 10, 2));

        Round tc = new Round(20, CARD).shadow();
        tc.setLayout(new BoxLayout(tc, BoxLayout.Y_AXIS));
        tc.setBorder(new EmptyBorder(18, 22, 20, 22));
        tc.add(lbl("PASSENGERS & EXTRAS", B, 11, MUTED));
        tc.add(gap(4));
        JLabel incl = lbl(fareType.label() + " fare includes " + fareType.bagKg() + " kg check-in + 7 kg cabin"
                + (freeMeal ? " and a free meal (choose veg / non-veg below)." : ". Extra baggage and meals can be added per traveller."), P, 12, MUTED);
        incl.setAlignmentX(0f);
        tc.add(incl);
        tc.add(gap(10));
        int seatIdx = 0;
        for (int i = 0; i < n; i++) {
            PaxType t = types.get(i);
            String seatTxt;
            if (t.seated()) {
                List<String> ss = new ArrayList<>();
                for (List<String> l : tripSeats) ss.add(l.get(seatIdx));
                seatIdx++;
                seatTxt = "   •   Seat " + String.join(" / ", ss);
            } else seatTxt = "   •   Lap infant, no seat";
            JLabel ttl = lbl("Traveller " + (i + 1) + "   •   " + t.label() + " (" + t.rule() + ")" + seatTxt, B, 14, PRIMARY);
            JTextField name = field(), age = field();
            if (i == 0) name.setText(me.name);
            names.add(name);
            ages.add(age);
            JPanel r = new JPanel(new GridBagLayout());
            r.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(0, 0, 0, 12);
            gc.weightx = 3; r.add(labeled("Full name (as on ID)", name), gc);
            gc.insets = new Insets(0, 0, 0, 0);
            gc.gridx = 1; gc.weightx = 1; r.add(labeled("Age", age), gc);
            JComboBox<String> bag = combo(Pricing.BAGS, 200);
            JComboBox<String> meal = combo(freeMeal ? new String[]{"Veg meal", "Non-veg meal"} : Pricing.MEALS, 200);
            if (!t.seated()) { bag.setEnabled(false); meal.setEnabled(false); }
            bagBoxes.add(bag);
            mealBoxes.add(meal);
            JPanel ex = new JPanel(new GridLayout(1, 2, 12, 0));
            ex.setOpaque(false);
            ex.add(labeled("Extra baggage", bag));
            ex.add(labeled(freeMeal ? "Meal (free)" : "In-flight meal", meal));
            ttl.setAlignmentX(0f); r.setAlignmentX(0f); ex.setAlignmentX(0f);
            tc.add(ttl);
            tc.add(gap(6));
            JComponent auto = (JComponent) Extras.autofill(me.email, name, age);
            auto.setAlignmentX(0f);
            tc.add(auto);
            tc.add(r);
            tc.add(gap(8));
            tc.add(ex);
            if (i < n - 1) tc.add(gap(18));
        }
        addTo(form, tc, 14);

        Round contact = new Round(20, CARD).shadow();
        contact.setLayout(new BorderLayout(0, 10));
        contact.setBorder(new EmptyBorder(18, 22, 20, 22));
        JPanel cg = new JPanel(new GridLayout(1, 2, 14, 0));
        cg.setOpaque(false);
        cg.add(detail("EMAIL (e-ticket sent here)", me.email));
        cg.add(detail("MOBILE", me.phone));
        contact.add(lbl("CONTACT DETAILS", B, 11, MUTED), BorderLayout.NORTH);
        contact.add(cg, BorderLayout.CENTER);
        addTo(form, contact, 14);

        JCheckBox insurance = new Extras.CB("Travel insurance · ₹199 / traveller");
        insurance.setOpaque(false);
        insurance.setFont(f(P, 13));
        int bal = Rewards.balance(me.email);
        JCheckBox redeem = new Extras.CB("Use my SkyPoints (" + bal + " available · 1 point = ₹1 · up to 30% of the amount)");
        redeem.setOpaque(false);
        redeem.setFont(f(P, 13));
        redeem.setEnabled(bal > 0);
        Round ac = new Round(20, CARD).shadow();
        ac.setLayout(new BorderLayout(0, 10));
        ac.setBorder(new EmptyBorder(18, 22, 20, 22));
        ac.add(lbl("PROTECTION & POINTS", B, 11, MUTED), BorderLayout.NORTH);
        ac.add(vbox(insurance, gap(6), redeem), BorderLayout.CENTER);
        addTo(form, ac, 14);

        JTextField promo = field();
        Btn apply = new Btn("Apply code", 2);
        JLabel promoMsg = lbl("Promo code is optional. Try SKY10, WELCOME500 or INTL2000.", P, 12, MUTED);
        JPanel pin = new JPanel(new BorderLayout(10, 0));
        pin.setOpaque(false);
        pin.add(promo, BorderLayout.CENTER);
        pin.add(apply, BorderLayout.EAST);
        Round pc = new Round(20, CARD).shadow();
        pc.setLayout(new BorderLayout());
        pc.setBorder(new EmptyBorder(18, 22, 18, 22));
        pc.add(vbox(lbl("PROMO CODE", B, 11, MUTED), gap(8), pin, gap(6), promoMsg));
        addTo(form, pc, 0);

        p.add(vscroll(form), BorderLayout.CENTER);

        String[] applied = {""};
        JPanel rows = vbox();
        JComboBox<String> payment = combo(new String[]{"UPI", "Credit / Debit Card", "Net Banking", "Wallet"}, 210);
        Extras.PaymentForm pf = new Extras.PaymentForm();
        payment.addActionListener(e -> pf.select((String) payment.getSelectedItem()));

        Supplier<int[][]> extras = () -> {
            int[] bags = new int[n], meals = new int[n];
            for (int i = 0; i < n; i++) {
                if (!types.get(i).seated()) continue;
                bags[i] = bagBoxes.get(i).getSelectedIndex();
                meals[i] = mealBoxes.get(i).getSelectedIndex() + (freeMeal ? 1 : 0);
            }
            return new int[][]{bags, meals};
        };
        Supplier<Quote> quote = () -> {
            int[][] ex = extras.get();
            return Pricing.quoteTrip(tripLegs, cls, types, tripSeats, ex[0], ex[1], insurance.isSelected(), applied[0], me.email,
                    true, fareType, redeem.isSelected());
        };

        Runnable update = () -> {
            Quote q = quote.get();
            rows.removeAll();
            rows.add(kv("Base fare (" + fareType.label() + ")", inr(q.base()), false));
            if (q.tax() > 0) rows.add(kv("GST", inr(q.tax()), false));
            rows.add(kv("Airport & convenience fees", inr(q.fees()), false));
            if (q.addons() > 0) rows.add(kv("Baggage, meals & insurance", inr(q.addons()), false));
            if (q.seatFees() > 0) rows.add(kv("Paid seats", inr(q.seatFees()), false));
            if (q.discount() > 0) rows.add(kv("Promo discount", "- " + inr(q.discount()), false));
            if (q.memberDiscount() > 0) rows.add(kv(Loyalty.of(me.email).name() + " member discount", "- " + inr(q.memberDiscount()), false));
            if (q.pointsUsed() > 0) rows.add(kv("SkyPoints (" + q.pointsUsed() + ")", "- " + inr(q.pointsUsed()), false));
            rows.add(new JSeparator());
            rows.add(kv("TOTAL", inr(q.total()), true));
            rows.add(lbl("You'll earn about " + (int) (q.total() / 100) + " SkyPoints", P, 11, MUTED));
            if (!q.promoMsg().isEmpty()) {
                promoMsg.setText(q.promoMsg());
                promoMsg.setForeground(q.promoOk() ? OK : DANGER);
            }
            rows.revalidate();
            rows.repaint();
        };
        for (int i = 0; i < n; i++) {
            bagBoxes.get(i).addActionListener(e -> update.run());
            mealBoxes.get(i).addActionListener(e -> update.run());
        }
        insurance.addActionListener(e -> update.run());
        redeem.addActionListener(e -> update.run());
        Runnable applyPromo = () -> {
            String code = promo.getText().trim();
            applied[0] = code;
            if (code.isEmpty()) {
                promoMsg.setText("No promo code entered — continuing without a discount is fine.");
                promoMsg.setForeground(MUTED);
            }
            update.run();
        };
        apply.addActionListener(e -> applyPromo.run());
        promo.addActionListener(e -> applyPromo.run());
        update.run();

        JPanel tripLines = vlist();
        for (int j = 0; j < picked.size(); j++) {
            Option o = picked.get(j);
            Seg g = segs.get(j);
            tripLines.add(lbl(g.from().code() + " → " + g.to().code() + " · " + o.dep().format(SHORT) + " " + o.dep().format(TF), B, 12, INK));
            tripLines.add(lbl(o.airlines() + " " + o.numbers() + (o.stops() > 0 ? " · 1 stop" : ""), P, 11, MUTED));
            tripLines.add(gap(4));
        }
        Round summary = new Round(20, CARD).shadow();
        summary.setBorder(new EmptyBorder(20, 20, 20, 20));
        summary.setLayout(new BorderLayout());
        summary.add(vbox(
                lbl("YOUR TRIP · " + tripType.toUpperCase(), B, 11, MUTED), gap(6),
                tripLines,
                lbl(cls + " · " + fareType.label() + " fare · " + paxText(), P, 12, MUTED), gap(16),
                lbl("Fare summary", B, 16, INK), gap(8), rows, gap(10),
                lbl("PAYMENT METHOD", B, 10, MUTED), gap(5), payment, gap(8), pf.panel));
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.setBorder(new EmptyBorder(0, 18, 0, 0));
        east.setPreferredSize(new Dimension(380, 10));
        JPanel sw = vlist();
        addTo(sw, summary, 0);
        east.add(vscroll(sw), BorderLayout.CENTER);
        p.add(east, BorderLayout.EAST);

        JCheckBox terms = new Extras.CB("I agree to the fare rules, cancellation policy and conditions of carriage");
        terms.setOpaque(false);
        terms.setFont(f(P, 12));
        Btn pay = new Btn("Confirm & pay  →", 0);
        pay.setFont(f(B, 15));
        pay.setPreferredSize(new Dimension(200, 46));
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(true);
        footer.setBackground(BG);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(10, 0, 0, 0)));
        footer.add(terms, BorderLayout.WEST);
        footer.add(pay, BorderLayout.EAST);
        p.add(footer, BorderLayout.SOUTH);

        pay.addActionListener(e -> {
            List<String> travellerNames = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String nm = names.get(i).getText().trim(), a = ages.get(i).getText().trim();
                if (!nm.matches("[\\p{L} .'-]{2,}")) { info("Please enter a valid name for traveller " + (i + 1) + "."); return; }
                if (!a.matches("\\d{1,3}") || Integer.parseInt(a) > 120) { info("Please enter a valid age for traveller " + (i + 1) + "."); return; }
                PaxType expected = types.get(i), actual = PaxType.forAge(Integer.parseInt(a));
                if (expected != actual) {
                    info("Traveller " + (i + 1) + " was booked as " + expected.label() + " (" + expected.rule() + "), but the age entered is a "
                            + actual.label().toLowerCase() + ". Please correct the age or change the traveller counts in the search.");
                    return;
                }
                travellerNames.add(nm);
            }
            if (!terms.isSelected()) { info("Please accept the fare rules to continue."); return; }
            String err = pf.validate();
            if (err != null) { info(err); return; }
            int[][] ex = extras.get();
            List<List<String>> seatCopy = new ArrayList<>();
            for (List<String> l : tripSeats) seatCopy.add(new ArrayList<>(l));
            Extras.processing(this, () -> {
                try {
                    Booking bk = Bookings.bookJourneys(me, tripLegs, tripJourney, cls, travellerNames, types, seatCopy, ex[0], ex[1],
                            insurance.isSelected(), applied[0], (String) payment.getSelectedItem(), fareType, redeem.isSelected());
                    for (int i = 0; i < travellerNames.size(); i++)
                        Extras.saveTraveller(me.email, travellerNames.get(i), ages.get(i).getText().trim());
                    goConfirm(bk);
                } catch (IllegalStateException ex2) {
                    info(ex2.getMessage());
                    legIdx = 0;
                    goSeats();
                } catch (IllegalArgumentException ex2) {
                    info(ex2.getMessage());
                }
            });
        });

        show("checkout", p);
    }

    // =========================================================
    // BOARDING PASS (confirmation + e-ticket dialog)
    // =========================================================
    static String journeyName(Booking b, int j) {
        if (b.journeyCount() == 1) return "";
        return b.roundTrip() ? (j == 0 ? "Outbound" : "Return") : "Trip " + (j + 1);
    }

    static String journeyLine(Booking b, int j) {
        List<Flight> l = b.journeyLegs(j);
        Flight a = l.get(0), z = l.get(l.size() - 1);
        StringBuilder nums = new StringBuilder(), via = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) { nums.append(" + "); via.append(i > 1 ? ", " : "").append(l.get(i).from().code()); }
            nums.append(l.get(i).no());
        }
        String nm = journeyName(b, j);
        return (nm.isEmpty() ? "" : nm + ":  ") + a.from().code() + " → " + z.to().code() + "  •  " + a.dep().format(SHORT) + " " + a.dep().format(TF)
                + " → " + z.arr().format(TF) + "  •  " + nums + (via.length() > 0 ? "  •  1 stop via " + via : "  •  Non-stop");
    }

    static JPanel boardingPass(Booking b) {
        List<Flight> legs = b.legs();
        List<Integer> jr = b.jr();
        Color accent = b.cancelled ? DANGER : b.status().equals("Completed") ? MUTED : PRIMARY;
        FareType ft = b.fare();

        Round pass = new Round(26, CARD).shadow();
        pass.setLayout(new BorderLayout(0, 14));
        pass.setBorder(new EmptyBorder(14, 14, 18, 14));

        Round head = new Round(18, accent == PRIMARY ? new Color(0x111827) : accent).gradient(accent == PRIMARY ? new Color(0x4338CA) : accent.darker());
        head.setLayout(new BorderLayout());
        head.setBorder(new EmptyBorder(16, 22, 16, 22));
        head.add(vbox(lbl("SKYINDIA  •  BOARDING PASS", B, 10, new Color(0xC7D2FE)), gap(4),
                lbl(b.chain(), B, 18, WHITE), gap(2),
                lbl(b.tripType() + (ft == null ? "" : "  ·  " + ft.label() + " fare") + "  ·  " + b.cls, P, 11, new Color(0xC7D2FE))), BorderLayout.WEST);
        head.add(vboxR(lbl("PNR", P, 10, new Color(0xC7D2FE)), lbl(b.pnr, B, 24, WHITE)), BorderLayout.EAST);
        pass.add(head, BorderLayout.NORTH);

        JPanel body = vlist();
        body.setBorder(new EmptyBorder(0, 12, 0, 12));

        for (int i = 0; i < legs.size(); i++) {
            Flight fx = legs.get(i);
            if (i > 0) {
                Flight prev = legs.get(i - 1);
                if (jr.get(i).equals(jr.get(i - 1))) {
                    long lay = ChronoUnit.MINUTES.between(prev.arr(), fx.dep());
                    JLabel lz = lbl("Connection in " + prev.to().city() + "  ·  layover " + Catalog.dur(lay), B, 12, AMBER);
                    lz.setAlignmentX(0f);
                    body.add(gap(4)); body.add(lz); body.add(gap(8));
                } else {
                    body.add(gap(6)); body.add(new DashLine()); body.add(gap(6));
                }
            }
            String seatTxt = b.legSeats(i).isEmpty() ? "-" : String.join(", ", b.legSeats(i));
            JLabel legHead = lbl((legs.size() > 1 ? "FLIGHT " + (i + 1) + " OF " + legs.size() + "  ·  " : "") + fx.airline() + " " + fx.no()
                    + "  ·  Seats " + seatTxt + (b.checkedIn(fx) ? "  ·  CHECKED IN" : ""), B, 11, b.checkedIn(fx) ? OK : MUTED);
            legHead.setAlignmentX(0f);
            body.add(legHead);
            body.add(gap(6));
            JPanel route = new JPanel(new BorderLayout(10, 0));
            route.setOpaque(false);
            route.add(vbox(lbl(fx.from().city(), B, 15, INK), lbl(fx.dep().format(TF), B, 26, accent), lbl(fx.from().code() + " • " + fx.dep().format(SD), P, 11, MUTED)), BorderLayout.WEST);
            route.add(new RouteLine(dur(fx.mins()), fx.intl() ? "International" : "Non-stop"), BorderLayout.CENTER);
            route.add(vboxR(lbl(fx.to().city(), B, 15, INK), lbl(fx.arr().format(TF), B, 26, accent), lbl(fx.to().code() + " • " + fx.arr().format(SD), P, 11, MUTED)), BorderLayout.EAST);
            route.setAlignmentX(0f);
            body.add(route);
        }
        body.add(gap(14));

        JPanel grid = new JPanel(new GridLayout(2, 4, 14, 12));
        grid.setOpaque(false);
        grid.add(detail("STATUS", b.status().toUpperCase()));
        grid.add(detail("CABIN", b.cls));
        grid.add(detail("FARE TYPE", ft == null ? "Standard" : ft.label()));
        grid.add(detail("PAYMENT", b.pay));
        grid.add(detail("INSURANCE", b.insurance ? "Yes" : "No"));
        grid.add(detail("PROMO", b.promo));
        grid.add(detail("SKYPOINTS USED", String.valueOf(b.pointsUsed)));
        grid.add(detail(b.cancelled ? "REFUNDED" : "TOTAL PAID", inr(b.cancelled ? b.refund : b.total)));
        grid.setAlignmentX(0f);
        body.add(grid);
        body.add(gap(12));

        JPanel pn = vlist();
        pn.add(lbl("PASSENGERS", B, 9, MUTED));
        for (int i = 0; i < b.names.size(); i++) {
            pn.add(gap(3));
            pn.add(lbl((i + 1) + ".  " + b.names.get(i) + "  (" + b.types().get(i).label() + ")  ·  " + Pricing.BAGS[b.bagOf(i)] + "  ·  " + b.mealOf(i), B, 12, INK));
        }
        pn.setAlignmentX(0f);
        body.add(pn);
        body.add(gap(14));
        body.add(new DashLine());
        body.add(gap(8));

        Flight f0 = legs.get(0);
        JPanel bottom = new JPanel(new BorderLayout(16, 0));
        bottom.setOpaque(false);
        bottom.add(new Barcode(b.pnr + f0.id()), BorderLayout.WEST);
        bottom.add(vboxR(lbl("Reach the airport " + (b.legs().stream().anyMatch(Flight::intl) ? "3 hours" : "2 hours") + " before departure.", P, 11, MUTED), gap(3),
                lbl("Carry a valid photo ID" + (b.legs().stream().anyMatch(Flight::intl) ? " and passport/visa." : "."), P, 11, MUTED)), BorderLayout.CENTER);
        bottom.setAlignmentX(0f);
        body.add(bottom);

        pass.add(body, BorderLayout.CENTER);
        pass.setPreferredSize(new Dimension(700, pass.getPreferredSize().height));
        return pass;
    }

    void goConfirm(Booking b) {
        JPanel s = stack();
        Round banner = new Round(18, new Color(0x16A34A)).gradient(new Color(0x10B981));
        banner.setBorder(new EmptyBorder(16, 24, 16, 24));
        banner.setLayout(new BorderLayout(14, 0));
        banner.add(new AnimCheck(56), BorderLayout.WEST);
        banner.add(vbox(lbl("Booking confirmed", B, 22, WHITE), gap(3),
                lbl("Your e-ticket has been generated. You earned about " + (int) (b.total / 100) + " SkyPoints. Have a great flight!", P, 13, new Color(0xD1FAE5))), BorderLayout.CENTER);
        addTo(s, banner, 10);
        addTo(s, new Steps(4), 6);

        JPanel centre = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        centre.setOpaque(false);
        centre.add(boardingPass(b));
        addTo(s, centre, 16);

        Btn ticket = new Btn("View e-ticket", 0), tl = new Btn("Trip timeline", 2), dest = new Btn("About " + b.legs().get(b.legs().size() - 1).to().city(), 2),
                bookings = new Btn("My bookings", 2), again = new Btn("Book another", 2);
        ticket.addActionListener(e -> showTicket(b));
        tl.addActionListener(e -> goTimeline(b));
        dest.addActionListener(e -> goDest(b.legs().get(b.legs().size() - 1).to()));
        bookings.addActionListener(e -> goBookings());
        again.addActionListener(e -> goSearch());
        addTo(s, flow(FlowLayout.CENTER, 10, ticket, tl, dest, bookings, again), 0);

        show("confirm", pageOf(s));
        confetti.burst();
    }

    // =========================================================
    // E-TICKET DIALOG
    // =========================================================
    void showTicket(Booking b) {
        JDialog d = new JDialog(this, "SkyIndia E-Ticket • " + b.pnr, true);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 22, 16, 22));
        JPanel wrapPass = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrapPass.setBackground(BG);
        wrapPass.add(boardingPass(b));
        JScrollPane sp = new JScrollPane(wrapPass);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        root.add(sp, BorderLayout.CENTER);

        Btn save = new Btn("Save as .txt", 0), pdf = new Btn("Boarding pass PDF", 2), inv = new Btn("Invoice", 2),
                copy = new Btn("Copy PNR", 2), close = new Btn("Close", 4);
        save.addActionListener(e -> {
            try {
                Path path = Bookings.export(b);
                JOptionPane.showMessageDialog(d, "Ticket saved to:\n" + path, "SkyIndia", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(d, "Could not save ticket: " + ex.getMessage(), "SkyIndia", JOptionPane.WARNING_MESSAGE);
            }
        });
        pdf.addActionListener(e -> {
            try {
                List<Flight> legs = b.legs();
                String[] legOpts = new String[legs.size()];
                for (int i = 0; i < legOpts.length; i++)
                    legOpts[i] = (i + 1) + ". " + legs.get(i).from().code() + " → " + legs.get(i).to().code() + " · " + legs.get(i).no();
                int li = 0;
                if (legs.size() > 1) {
                    Object ch = JOptionPane.showInputDialog(d, "Which flight?", "Boarding pass", JOptionPane.QUESTION_MESSAGE, null, legOpts, legOpts[0]);
                    if (ch == null) return;
                    li = Arrays.asList(legOpts).indexOf(ch);
                }
                String who = b.names.get(0);
                if (b.names.size() > 1) {
                    Object ch = JOptionPane.showInputDialog(d, "Which passenger?", "Boarding pass", JOptionPane.QUESTION_MESSAGE, null,
                            b.names.toArray(new String[0]), b.names.get(0));
                    if (ch == null) return;
                    who = (String) ch;
                }
                Path path = Bookings.exportPdf(b, li, who);
                JOptionPane.showMessageDialog(d, "Boarding pass saved to:\n" + path, "SkyIndia", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(d, "Could not save boarding pass: " + ex.getMessage(), "SkyIndia", JOptionPane.WARNING_MESSAGE);
            }
        });
        inv.addActionListener(e -> {
            try {
                JOptionPane.showMessageDialog(d, "Invoice saved to:\n" + Extras.invoice(b), "SkyIndia", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(d, "Could not save invoice: " + ex.getMessage(), "SkyIndia", JOptionPane.WARNING_MESSAGE);
            }
        });
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(b.pnr), null));
        close.addActionListener(e -> d.dispose());
        root.add(flow(FlowLayout.CENTER, 10, save, pdf, inv, copy, close), BorderLayout.SOUTH);

        d.setContentPane(root);
        d.pack();
        Rectangle sc = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        d.setSize(Math.min(Math.max(d.getWidth(), 780), sc.width), Math.min(Math.max(d.getHeight(), 600), sc.height - 40));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // =========================================================
    // MY BOOKINGS
    // =========================================================
    void goBookings() {
        nav(2);
        List<Booking> all = Bookings.of(me.email);
        JPanel s = stack();
        addTo(s, header("My bookings", "Manage trips, tickets, changes and cancellations.", null), 14);

        long up = all.stream().filter(b -> b.status().equals("Upcoming")).count();
        long done = all.stream().filter(b -> b.status().equals("Completed")).count();
        double spent = all.stream().filter(b -> !b.cancelled).mapToDouble(b -> b.total).sum();
        JPanel stats = new JPanel(new GridLayout(1, 4, 14, 0));
        stats.setOpaque(false);
        String[][] data = {{"Total trips", String.valueOf(up + done)}, {"Upcoming", String.valueOf(up)},
                {"Completed", String.valueOf(done)}, {"Total spent", inr(spent)}};
        for (String[] it : data) {
            Round c = new Round(16, CARD).shadow();
            c.setBorder(new EmptyBorder(14, 18, 16, 18));
            c.setLayout(new BorderLayout());
            c.add(vbox(lbl(it[0], P, 12, MUTED), gap(4), lbl(it[1], B, 22, PRIMARY)));
            stats.add(c);
        }
        addTo(s, stats, 14);

        JPanel list = vlist();
        JTextField search = field();
        search.setPreferredSize(new Dimension(240, 38));
        search.setMaximumSize(new Dimension(240, 38));
        search.setToolTipText("Search by PNR, city or airline");
        Runnable[] refill = {null};

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        chips.setOpaque(false);
        for (String st : new String[]{"All", "Upcoming", "Completed", "Cancelled"}) {
            long n = st.equals("All") ? all.size() : all.stream().filter(b -> b.status().equals(st)).count();
            Btn chip = new Btn(st + " (" + n + ")", st.equals(filter) ? 0 : 4);
            chip.setBorder(new EmptyBorder(8, 16, 8, 16));
            chip.addActionListener(e -> { filter = st; goBookings(); });
            chips.add(chip);
        }
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(chips, BorderLayout.WEST);
        JPanel sw = new JPanel(new GridBagLayout());
        sw.setOpaque(false);
        sw.add(search);
        bar.add(sw, BorderLayout.EAST);
        addTo(s, bar, 12);

        refill[0] = () -> {
            String q = search.getText().trim().toLowerCase();
            list.removeAll();
            int shown = 0;
            for (Booking b : all) {
                if (!filter.equals("All") && !b.status().equals(filter)) continue;
                StringBuilder hay = new StringBuilder(b.pnr + " " + b.chain());
                for (Flight fx : b.legs()) hay.append(" ").append(fx.from().city()).append(" ").append(fx.to().city()).append(" ").append(fx.airline()).append(" ").append(fx.no());
                if (!q.isEmpty() && !hay.toString().toLowerCase().contains(q)) continue;
                list.add(bookingCard(b));
                list.add(gap(10));
                shown++;
            }
            if (shown == 0) {
                Round e = new Round(18, CARD).shadow();
                e.setBorder(new EmptyBorder(30, 30, 30, 30));
                e.setLayout(new BorderLayout());
                e.setAlignmentX(0f);
                Btn go = new Btn("Find a flight", 0);
                go.addActionListener(ev -> goSearch());
                e.add(vbox(icoLabel("plane", 36, PRIMARY), gap(8),
                        lbl(all.isEmpty() ? "No bookings yet" : "No bookings match", B, 18, INK), gap(4),
                        lbl(all.isEmpty() ? "Search for a flight to start your journey." : "Try another filter or search term.", P, 13, MUTED),
                        gap(12), go));
                list.add(e);
            }
            list.revalidate();
            list.repaint();
        };
        onChange(search, () -> refill[0].run());
        refill[0].run();
        addTo(s, list, 0);

        show("bookings", pageOf(s));
    }

    JPanel bookingCard(Booking b) {
        Color sc = switch (b.status()) {
            case "Upcoming" -> PRIMARY;
            case "Completed" -> OK;
            default -> DANGER;
        };
        FareType ft = b.fare();
        Round card = new Round(18, CARD).shadow().lift();
        card.setLayout(new BorderLayout(18, 0));
        card.setBorder(new EmptyBorder(16, 22, 18, 22));
        card.setAlignmentX(0f);

        JPanel t = flow(FlowLayout.LEFT, 8);
        ((FlowLayout) t.getLayout()).setHgap(8);
        t.add(lbl(b.chain(), B, 18, INK));
        t.add(pill(b.status().toUpperCase(), sc));
        t.add(pill(b.tripType().toUpperCase(), BLUE));
        if (ft != null) t.add(pill(ft.label().toUpperCase(), ft == FareType.PREMIUM ? AMBER : PRIMARY2));
        if (b.legs().stream().anyMatch(Flight::intl)) t.add(pill("INTL", AMBER));
        JPanel info = vlist();
        info.add(t);
        info.add(gap(6));
        for (int j = 0; j < b.journeyCount(); j++) {
            info.add(lbl(journeyLine(b, j), P, 12, INK));
            info.add(gap(2));
        }
        info.add(gap(2));
        info.add(lbl("PNR " + b.pnr + "  •  " + b.cls + "  •  " + b.names.size() + " traveller" + (b.names.size() > 1 ? "s" : "")
                + "  •  Bags: " + b.bagText() + "  •  Meal: " + b.mealText(), P, 12, MUTED));
        card.add(info, BorderLayout.CENTER);

        Btn ticket = new Btn("Ticket", 2);
        ticket.setBorder(new EmptyBorder(7, 14, 7, 14));
        ticket.addActionListener(e -> showTicket(b));
        Btn tl = new Btn("Timeline", 2);
        tl.setBorder(new EmptyBorder(7, 14, 7, 14));
        tl.addActionListener(e -> goTimeline(b));
        JPanel row1 = flow(FlowLayout.RIGHT, 6, ticket, tl);
        JPanel row2 = flow(FlowLayout.RIGHT, 6);
        if (b.status().equals("Upcoming")) {
            Flight nl = Bookings.nextLeg(b);
            boolean checked = nl != null && b.checkedIn(nl);
            Btn ci = new Btn(checked ? "Checked in" : "Web check-in", 2);
            ci.setBorder(new EmptyBorder(7, 14, 7, 14));
            ci.setEnabled(!checked && Bookings.checkinOpen(b));
            ci.setToolTipText(checked ? "Boarding pass issued" : "Opens 48 hours before departure, closes 1 hour before");
            ci.addActionListener(e -> {
                try { Bookings.checkIn(b); showTicket(b); goBookings(); }
                catch (IllegalStateException ex) { info(ex.getMessage()); }
            });
            row1.add(ci);

            Btn change = new Btn("Change", 2);
            change.setBorder(new EmptyBorder(7, 14, 7, 14));
            change.addActionListener(e -> goChange(b));
            row2.add(change);

            Btn cancel = new Btn("Cancel", 5);
            cancel.setBorder(new EmptyBorder(7, 14, 7, 14));
            cancel.addActionListener(e -> {
                double pct = Bookings.refundPct(b);
                int ans = JOptionPane.showConfirmDialog(this,
                        "Cancel booking " + b.pnr + "?\n\n" + (ft == null ? "Standard" : ft.label()) + " fare refund: " + inr(b.total * pct)
                                + " (" + Math.round(pct * 100) + "%)"
                                + (b.pointsUsed > 0 ? "\nSkyPoints used on this booking are returned in proportion." : ""),
                        "Cancel booking", JOptionPane.YES_NO_OPTION);
                if (ans == JOptionPane.YES_OPTION) {
                    Bookings.cancel(b);
                    goBookings();
                }
            });
            row2.add(cancel);
        }
        JPanel east = vboxR(lbl(inr(b.total), B, 21, b.cancelled ? MUTED : PRIMARY));
        if (b.cancelled) east.add(lbl("Refunded " + inr(b.refund), B, 11, OK));
        east.add(gap(8));
        east.add(row1);
        east.add(gap(4));
        east.add(row2);
        row1.setAlignmentX(1f);
        row2.setAlignmentX(1f);
        JPanel ew = new JPanel(new GridBagLayout());
        ew.setOpaque(false);
        ew.add(east);
        card.add(ew, BorderLayout.EAST);
        return card;
    }

    // =========================================================
    // CHANGE / RESCHEDULE A BOOKING
    // =========================================================
    void goChange(Booking b) {
        nav(2);
        List<Flight> legs = b.legs();
        List<Integer> idx = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            Flight fx = legs.get(i);
            if (ChronoUnit.HOURS.between(LocalDateTime.now(), fx.dep()) >= 4) {
                idx.add(i);
                labels.add("Flight " + (i + 1) + ": " + fx.from().code() + " → " + fx.to().code() + " · " + fx.no() + " · " + fx.dep().format(SHORT) + " " + fx.dep().format(TF));
            }
        }
        if (idx.isEmpty()) { info("Changes close 4 hours before departure, so none of this booking's flights can be changed."); return; }
        FareType ft = b.fare();

        JPanel s = stack();
        Btn back = new Btn("←  Back to bookings", 4);
        back.addActionListener(e -> goBookings());
        addTo(s, header("Change booking " + b.pnr, b.chain() + "  ·  " + (ft == null ? "Standard fare" : ft.label() + " fare"), back), 8);
        String feeTxt = ft == null ? "Standard change fee applies" : ft.changeFeeFactor() == 0 ? "Free changes — you only pay any fare difference"
                : ft.changeFeeFactor() >= 1 ? "Full change fee applies" : "Half change fee applies";
        addTo(s, lbl(feeTxt + ". Pick the flight to move, a new date and a new flight on the same route. If the new flight is cheaper, you get the difference back.", P, 13, MUTED), 14);

        JComboBox<String> legBox = combo(labels.toArray(new String[0]), 380);
        String[] days = dayLabels(60);
        JComboBox<String> dateBox = combo(days, 200);
        setDay(dateBox, legs.get(idx.get(0)).dep().toLocalDate());
        legBox.addActionListener(e -> setDay(dateBox, legs.get(idx.get(legBox.getSelectedIndex())).dep().toLocalDate()));
        Btn find = new Btn("Find flights  →", 0);

        Round pick = new Round(20, CARD).shadow();
        pick.setBorder(new EmptyBorder(18, 22, 20, 22));
        pick.setLayout(new BorderLayout(14, 0));
        JPanel pf = new JPanel(new GridBagLayout());
        pf.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 0, 12);
        gc.weightx = 2; pf.add(labeled("Flight to change", legBox), gc);
        gc.gridx = 1; gc.weightx = 1; pf.add(labeled("New date", dateBox), gc);
        gc.gridx = 2; gc.weightx = 0; gc.insets = new Insets(0, 0, 0, 0); pf.add(labeled(" ", find), gc);
        pick.add(pf);
        addTo(s, pick, 14);

        JPanel results = vlist();
        JPanel detailHolder = vlist();
        find.addActionListener(e -> {
            int li = idx.get(legBox.getSelectedIndex());
            Flight old = legs.get(li);
            LocalDate d = dayOf(dateBox);
            double mult = Pricing.CLASS_X.get(b.cls);
            results.removeAll();
            detailHolder.removeAll();
            results.add(lbl("Flights on " + old.from().code() + " → " + old.to().code() + " · " + d.format(DF), B, 16, INK));
            results.add(gap(8));
            int shown = 0;
            for (Flight nf : Catalog.search(old.from(), old.to(), d)) {
                Round c = new Round(16, CARD).shadow();
                c.setBorder(new EmptyBorder(12, 18, 12, 18));
                c.setLayout(new BorderLayout(14, 0));
                c.setAlignmentX(0f);
                boolean same = nf.id().equals(old.id());
                c.add(vbox(lbl(nf.airline() + "  " + nf.no() + (same ? "   (current flight)" : ""), B, 14, INK), gap(2),
                        lbl(nf.dep().format(TF) + " → " + nf.arr().format(TF) + "  ·  " + dur(nf.mins()) + "  ·  Non-stop", P, 12, MUTED)), BorderLayout.CENTER);
                Btn sel = new Btn(same ? "Change seats" : "Select", 0);
                sel.addActionListener(ev -> changeDetail(b, li, nf, detailHolder));
                JPanel east = vboxR(lbl(inr(nf.fare() * mult), B, 18, PRIMARY), lbl("per traveller (base)", P, 10, MUTED), gap(4), sel);
                JPanel ew = new JPanel(new GridBagLayout());
                ew.setOpaque(false);
                ew.add(east);
                c.add(ew, BorderLayout.EAST);
                results.add(c);
                results.add(gap(8));
                shown++;
            }
            if (shown == 0) results.add(lbl("No flights found for that date.", P, 13, MUTED));
            results.revalidate();
            results.repaint();
            detailHolder.revalidate();
            detailHolder.repaint();
        });
        addTo(s, results, 12);
        addTo(s, detailHolder, 0);
        show("change", pageOf(s));
    }

    void changeDetail(Booking b, int li, Flight nf, JPanel holder) {
        int need = b.seatedCount();
        Flight old = b.legs().get(li);
        boolean same = nf.id().equals(old.id());
        Set<String> sel = new TreeSet<>();
        if (same) sel.addAll(b.legSeats(li));
        Set<String> mine = same ? new HashSet<>(b.legSeats(li)) : new HashSet<>();
        FareType ft = b.fare();

        JPanel qp = vlist();
        Btn confirm = new Btn("Confirm change", 0);
        confirm.setPreferredSize(new Dimension(220, 44));
        Runnable upd = () -> {
            qp.removeAll();
            List<String> ns = new ArrayList<>(sel);
            if (ns.size() != need) {
                qp.add(lbl("Select " + need + " seat" + (need > 1 ? "s" : "") + " for the new flight.", P, 13, MUTED));
                confirm.setEnabled(false);
            } else {
                ModQuote q = Bookings.modifyQuote(b, li, nf, ns);
                if (!q.ok()) {
                    qp.add(wrap(q.error(), 260, B, 13, DANGER));
                    confirm.setEnabled(false);
                } else {
                    qp.add(kv("Change fee", inr(q.changeFee()), false));
                    qp.add(kv("Fare difference", signed(q.fareDiff()), false));
                    qp.add(kv("Seat difference", signed(q.seatDiff()), false));
                    qp.add(new JSeparator());
                    qp.add(kv(q.payNow() >= 0 ? "TO PAY" : "YOU RECEIVE", inr(Math.abs(q.payNow())), true));
                    confirm.setEnabled(true);
                }
            }
            qp.revalidate();
            qp.repaint();
        };
        confirm.addActionListener(e -> {
            try {
                ModQuote q = Bookings.modify(b, li, nf, new ArrayList<>(sel));
                ok(q.payNow() >= 0 ? "Change confirmed. " + inr(q.payNow()) + " charged to your original payment method."
                        : "Change confirmed. " + inr(q.refund()) + " will be refunded to your original payment method.");
                goBookings();
            } catch (IllegalStateException ex) {
                info(ex.getMessage());
            }
        });

        Round card = new Round(22, CARD).shadow();
        card.setBorder(new EmptyBorder(18, 22, 20, 22));
        card.setLayout(new BorderLayout(20, 0));
        JPanel left = vlist();
        left.add(lbl("Choose seats on " + nf.no() + " · " + nf.dep().format(SHORT) + " " + nf.dep().format(TF), B, 16, INK));
        left.add(gap(8));
        JPanel lg = seatLegend(nf, b.cls, ft);
        lg.setAlignmentX(0f);
        left.add(lg);
        JComponent grid = seatGrid(nf, sel, need, mine, b.cls, ft, upd);
        grid.setAlignmentX(0f);
        left.add(grid);
        card.add(left, BorderLayout.CENTER);
        JPanel right = vlist();
        right.setPreferredSize(new Dimension(300, 10));
        right.add(lbl("COST OF CHANGE", B, 10, MUTED));
        right.add(gap(8));
        right.add(qp);
        right.add(gap(14));
        right.add(confirm);
        JPanel rw = new JPanel(new BorderLayout());
        rw.setOpaque(false);
        rw.add(right, BorderLayout.NORTH);
        rw.setPreferredSize(new Dimension(300, 10));
        card.add(rw, BorderLayout.EAST);

        upd.run();
        holder.removeAll();
        card.setAlignmentX(0f);
        holder.add(card);
        holder.revalidate();
        holder.repaint();
    }

    // =========================================================
    // TRIP TIMELINE
    // =========================================================
    void goTimeline(Booking sel) {
        nav(3);
        List<Booking> mine = new ArrayList<>();
        for (Booking b : Bookings.of(me.email)) if (!b.cancelled) mine.add(b);
        mine.sort(Comparator.comparing((Booking b) -> b.status().equals("Upcoming") ? 0 : 1).thenComparing(b -> b.legs().get(0).dep()));

        JPanel s = stack();
        addTo(s, header("Trip timeline", "Check-in window, airport arrival, boarding, flights and connections in order.", null), 16);
        if (mine.isEmpty()) {
            Round e = new Round(18, CARD).shadow();
            e.setBorder(new EmptyBorder(30, 30, 30, 30));
            e.setLayout(new BorderLayout());
            Btn go = new Btn("Find a flight", 0);
            go.addActionListener(ev -> goSearch());
            e.add(vbox(icoLabel("clock", 36, PRIMARY), gap(8), lbl("No trips to show", B, 18, INK), gap(4),
                    lbl("Book a flight and its timeline will appear here.", P, 13, MUTED), gap(12), go));
            addTo(s, e, 0);
            show("timeline", pageOf(s));
            return;
        }
        Booking b = sel != null && mine.contains(sel) ? sel : mine.get(0);

        String[] opts = new String[mine.size()];
        for (int i = 0; i < opts.length; i++) {
            Booking x = mine.get(i);
            opts[i] = x.pnr + "  ·  " + x.chain() + "  ·  " + x.legs().get(0).dep().format(SD) + "  ·  " + x.status();
        }
        JComboBox<String> box = combo(opts, 520);
        box.setSelectedIndex(mine.indexOf(b));
        box.addActionListener(e -> goTimeline(mine.get(box.getSelectedIndex())));
        addTo(s, labeled("Booking", box), 14);

        Flight nl = Bookings.nextLeg(b);
        if (nl != null && !b.cancelled) {
            Round key = new Round(18, CARD).shadow();
            key.setBorder(new EmptyBorder(16, 22, 18, 22));
            key.setLayout(new BorderLayout(0, 10));
            key.add(lbl("Next flight: " + nl.no() + " · " + nl.from().code() + " → " + nl.to().code(), B, 15, INK), BorderLayout.NORTH);
            JPanel g = new JPanel(new GridLayout(1, 4, 14, 0));
            g.setOpaque(false);
            g.add(detail("WEB CHECK-IN OPENS", nl.dep().minusHours(48).format(TLF)));
            g.add(detail("ARRIVE AT AIRPORT BY", nl.dep().minusHours(nl.intl() ? 3 : 2).format(TLF)));
            g.add(detail("CHECK-IN & BAG DROP CLOSE", nl.dep().minusHours(1).format(TLF)));
            g.add(detail("GATE CLOSES", nl.dep().minusMinutes(25).format(TLF)));
            key.add(g, BorderLayout.CENTER);
            addTo(s, key, 14);
        }

        Round card = new Round(22, CARD).shadow();
        card.setBorder(new EmptyBorder(20, 24, 12, 24));
        card.setLayout(new BorderLayout(0, 12));
        Btn ticket = new Btn("View ticket", 2);
        ticket.addActionListener(e -> showTicket(b));
        Airport last = b.legs().get(b.legs().size() - 1).to();
        Btn dest = new Btn("About " + last.city(), 2);
        dest.addActionListener(e -> goDest(last));
        JPanel th = new JPanel(new BorderLayout());
        th.setOpaque(false);
        th.add(vbox(lbl(b.chain(), B, 18, INK), gap(3), lbl(b.tripType() + "  ·  PNR " + b.pnr, P, 12, MUTED)), BorderLayout.WEST);
        th.add(flow(FlowLayout.RIGHT, 8, dest, ticket), BorderLayout.EAST);
        card.add(th, BorderLayout.NORTH);

        List<Event> ev = Timeline.of(b);
        LocalDateTime now = LocalDateTime.now();
        boolean marked = false;
        JPanel rowsP = vlist();
        for (int i = 0; i < ev.size(); i++) {
            Event e = ev.get(i);
            boolean past = e.at().isBefore(now);
            boolean next = !marked && !past;
            if (next) marked = true;
            Color col = switch (e.kind()) {
                case "book" -> PRIMARY;
                case "checkin" -> BLUE;
                case "airport", "board" -> AMBER;
                case "gate" -> DANGER;
                case "dep" -> PRIMARY2;
                case "arr" -> OK;
                default -> MUTED;
            };
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setAlignmentX(0f);
            JPanel when = new JPanel(new BorderLayout());
            when.setOpaque(false);
            when.setBorder(new EmptyBorder(8, 0, 0, 0));
            when.setPreferredSize(new Dimension(150, 10));
            when.add(lbl(e.at().format(TLF), B, 12, past ? MUTED : INK), BorderLayout.NORTH);
            row.add(when, BorderLayout.WEST);
            JPanel inner = new JPanel(new BorderLayout(8, 0));
            inner.setOpaque(false);
            inner.add(new TLDot(col, i == 0, i == ev.size() - 1, past), BorderLayout.WEST);
            JPanel tx = vlist();
            tx.setBorder(new EmptyBorder(6, 0, 14, 0));
            JPanel tl = flow(FlowLayout.LEFT, 8, lbl(e.title(), B, 14, past ? MUTED : INK));
            if (next) tl.add(pill("NEXT", PRIMARY));
            if (past) tl.add(icoLabel("check", 14, OK));
            tl.setAlignmentX(0f);
            tx.add(tl);
            tx.add(lbl(e.detail(), P, 12, MUTED));
            inner.add(tx, BorderLayout.CENTER);
            row.add(inner, BorderLayout.CENTER);
            rowsP.add(row);
        }
        card.add(rowsP, BorderLayout.CENTER);
        addTo(s, card, 0);
        show("timeline", pageOf(s));
    }

    // =========================================================
    // DESTINATION INFO
    // =========================================================
    void goDest(Airport sel) {
        nav(5);
        Airport a = sel != null ? sel : segs.get(segs.size() - 1).to();
        JPanel s = stack();
        addTo(s, header("Destination info", "Local time, typical weather, baggage and visa notes (reference data, not live).", null), 14);

        JComboBox<Airport> box = combo(Catalog.AIRPORTS.toArray(new Airport[0]), 260);
        box.setSelectedItem(a);
        box.addActionListener(e -> goDest((Airport) box.getSelectedItem()));
        int[] month = {LocalDate.now().getMonthValue()};
        if (sel == null && segs.size() > 0) month[0] = segs.get(segs.size() - 1).date().getMonthValue();
        String[] mn = new String[12];
        for (int i = 0; i < 12; i++) mn[i] = Month.of(i + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        JComboBox<String> mbox = combo(mn, 180);
        mbox.setSelectedIndex(month[0] - 1);

        DestInfo.Info in = DestInfo.of(a);
        ZonedDateTime local = ZonedDateTime.now(DestInfo.zone(a));
        Btn book = new Btn("Search flights to " + a.city() + "  →", 0);
        book.addActionListener(e -> {
            tripType = "One-way";
            Airport from = segs.get(0).from().equals(a) ? Catalog.byCode("DEL").equals(a) ? Catalog.byCode("BOM") : Catalog.byCode("DEL") : segs.get(0).from();
            segs.clear();
            segs.add(new Seg(from, a, LocalDate.now().plusDays(7)));
            goSearch();
        });
        JPanel pickRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        pickRow.setOpaque(false);
        pickRow.add(labeled("Destination", box));
        pickRow.add(labeled("Weather month", mbox));
        pickRow.add(labeled(" ", book));
        addTo(s, pickRow, 14);

        JPanel top = new JPanel(new GridLayout(1, 3, 14, 0));
        top.setOpaque(false);
        Round t1 = new Round(20, CARD).shadow();
        t1.setBorder(new EmptyBorder(18, 22, 18, 22));
        t1.setLayout(new BorderLayout());
        t1.add(vbox(lbl("LOCAL TIME · " + a.city().toUpperCase(), B, 10, MUTED), gap(6),
                lbl(local.format(TF), B, 34, PRIMARY), gap(2),
                lbl(local.format(DF), P, 12, INK), gap(4),
                lbl(DestInfo.offsetVsIndia(a) + "  ·  " + in.zone(), P, 12, MUTED)));
        top.add(t1);

        Round t2 = new Round(20, CARD).shadow();
        t2.setBorder(new EmptyBorder(18, 22, 18, 22));
        t2.setLayout(new BorderLayout());
        JPanel wbody = vlist();
        Runnable showW = () -> {
            DestInfo.Weather w = DestInfo.weather(a, mbox.getSelectedIndex() + 1);
            wbody.removeAll();
            wbody.add(lbl("TYPICAL WEATHER · " + mn[mbox.getSelectedIndex()].toUpperCase(), B, 10, MUTED));
            wbody.add(gap(6));
            wbody.add(lbl(w.hi() + "° / " + w.lo() + "°C", B, 30, INK));
            wbody.add(gap(2));
            wbody.add(lbl(w.cond(), B, 13, PRIMARY));
            wbody.add(gap(4));
            wbody.add(wrap(w.note(), 260, P, 12, MUTED));
            wbody.revalidate();
            wbody.repaint();
        };
        mbox.addActionListener(e -> showW.run());
        showW.run();
        t2.add(wbody);
        top.add(t2);

        Round t3 = new Round(20, CARD).shadow();
        t3.setBorder(new EmptyBorder(18, 22, 18, 22));
        t3.setLayout(new BorderLayout());
        t3.add(vbox(lbl("GOOD TO KNOW", B, 10, MUTED), gap(8),
                detail("CURRENCY", in.currency()), gap(8),
                detail("POWER PLUG", in.plug()), gap(8),
                wrap(in.climate(), 260, P, 12, MUTED)));
        top.add(t3);
        addTo(s, top, 14);

        Round visa = new Round(20, CARD).shadow();
        visa.setBorder(new EmptyBorder(18, 22, 18, 22));
        visa.setLayout(new BorderLayout(0, 8));
        visa.add(lbl("VISA & ENTRY (FOR INDIAN PASSPORT HOLDERS)", B, 10, MUTED), BorderLayout.NORTH);
        visa.add(wrap(in.visa(), 900, P, 13, INK), BorderLayout.CENTER);
        addTo(s, visa, 14);

        Round bag = new Round(20, CARD).shadow();
        bag.setBorder(new EmptyBorder(18, 22, 18, 22));
        bag.setLayout(new BorderLayout(0, 8));
        bag.add(lbl("BAGGAGE NOTES", B, 10, MUTED), BorderLayout.NORTH);
        bag.add(wrap(in.baggage(), 900, P, 13, INK), BorderLayout.CENTER);
        addTo(s, bag, 0);

        show("dest", pageOf(s));
    }

    // =========================================================
    // SAVED ROUTES & PRICE ALERTS
    // =========================================================
    void goSaved() {
        nav(4);
        JPanel s = stack();
        addTo(s, header("Saved routes", "Your wishlist. Add a target fare to turn any route into a price alert.", null), 16);

        Airport[] airports = Catalog.AIRPORTS.toArray(new Airport[0]);
        JComboBox<Airport> fb = combo(airports, 180), tb = combo(airports, 180);
        fb.setSelectedItem(Catalog.byCode("DEL"));
        tb.setSelectedItem(Catalog.byCode("GOI"));
        JTextField target = field();
        Btn add = new Btn("Save route", 0);
        add.addActionListener(e -> {
            Airport a = (Airport) fb.getSelectedItem(), b = (Airport) tb.getSelectedItem();
            if (a.equals(b)) { info("Pick two different airports."); return; }
            if (!a.india() && !b.india()) { info("At least one airport must be in India."); return; }
            double t = 0;
            if (!target.getText().isBlank()) {
                try { t = Double.parseDouble(target.getText().trim().replace(",", "")); }
                catch (NumberFormatException ex) { info("Enter the target fare as a number."); return; }
            }
            SavedRoutes.save(me.email, a, b, t);
            goSaved();
        });
        Round form = new Round(20, CARD).shadow();
        form.setBorder(new EmptyBorder(18, 22, 20, 22));
        form.setLayout(new BorderLayout(0, 10));
        form.add(lbl("ADD A ROUTE", B, 11, MUTED), BorderLayout.NORTH);
        JPanel fr = new JPanel(new GridLayout(1, 4, 12, 0));
        fr.setOpaque(false);
        fr.add(labeled("From", fb));
        fr.add(labeled("To", tb));
        fr.add(labeled("Alert when fare ≤ (₹, optional)", target));
        fr.add(labeled(" ", add));
        form.add(fr, BorderLayout.CENTER);
        addTo(s, form, 16);

        List<SavedRoute> list = new ArrayList<>(SavedRoutes.of(me.email));
        if (list.isEmpty()) {
            Round e = new Round(18, CARD).shadow();
            e.setBorder(new EmptyBorder(26, 28, 26, 28));
            e.setLayout(new BorderLayout());
            e.add(vbox(lbl("No saved routes yet", B, 17, INK), gap(4), lbl("Save a route above, or from the results page.", P, 13, MUTED)));
            addTo(s, e, 0);
        }
        for (SavedRoute r : list) {
            Airport a = Catalog.byCode(r.from()), b = Catalog.byCode(r.to());
            Cheap c = Catalog.cheapest(a, b, 30);
            boolean hit = c != null && r.target() > 0 && c.fare() <= r.target();
            Round card = new Round(18, CARD).shadow().lift();
            card.setBorder(new EmptyBorder(16, 22, 16, 22));
            card.setLayout(new BorderLayout(18, 0));
            card.setAlignmentX(0f);
            JPanel t = flow(FlowLayout.LEFT, 8, lbl(a.code() + "  →  " + b.code(), B, 19, INK));
            if (r.target() > 0) t.add(pill(hit ? "ALERT: BELOW TARGET" : "ALERT AT " + inr(r.target()), hit ? OK : BLUE));
            card.add(vbox(t, gap(4), lbl(a.city() + " to " + b.city(), P, 12, MUTED), gap(4),
                    lbl(c == null ? "No fares available in the next 30 days" : "Cheapest in the next 30 days: " + inr(c.fare()) + " on " + c.date().format(DF), B, 13, hit ? OK : INK)), BorderLayout.CENTER);
            Btn go = new Btn("Search", 0);
            go.addActionListener(e -> {
                tripType = "One-way";
                segs.clear();
                segs.add(new Seg(a, b, c != null ? c.date() : LocalDate.now().plusDays(7)));
                startSearch();
            });
            Btn alert = new Btn(r.target() > 0 ? "Edit alert" : "Set alert", 2);
            alert.addActionListener(e -> {
                String in = JOptionPane.showInputDialog(this, "Alert me when the fare is at or below (₹). Enter 0 to clear:", (long) r.target());
                if (in == null) return;
                try { SavedRoutes.save(me.email, a, b, Double.parseDouble(in.trim().replace(",", ""))); goSaved(); }
                catch (NumberFormatException ex) { info("Please enter a number."); }
            });
            Btn rm = new Btn("Remove", 5);
            rm.addActionListener(e -> { SavedRoutes.remove(me.email, r); goSaved(); });
            JPanel ew = new JPanel(new GridBagLayout());
            ew.setOpaque(false);
            ew.add(flow(FlowLayout.RIGHT, 8, go, alert, rm));
            card.add(ew, BorderLayout.EAST);
            addTo(s, card, 10);
        }
        show("saved", pageOf(s));
    }

    // =========================================================
    // REWARDS & REFERRALS
    // =========================================================
    void goRewards() {
        nav(6);
        JPanel s = stack();
        addTo(s, header("Rewards & referrals", "Spend SkyPoints on fares and earn bonus points when friends join.", null), 16);
        addTo(s, Extras.loyalty(me, () -> { }), 16);

        JPanel stats = new JPanel(new GridLayout(1, 4, 14, 0));
        stats.setOpaque(false);
        String[][] d = {{"Points to spend", String.valueOf(Rewards.balance(me.email))}, {"Earned from trips", String.valueOf(Rewards.earned(me.email))},
                {"Bonus points", String.valueOf(me.bonusPoints)}, {"Points spent", String.valueOf(me.pointsSpent)}};
        for (String[] it : d) {
            Round c = new Round(16, CARD).shadow();
            c.setBorder(new EmptyBorder(14, 18, 16, 18));
            c.setLayout(new BorderLayout());
            c.add(vbox(lbl(it[0], P, 12, MUTED), gap(4), lbl(it[1], B, 22, PRIMARY)));
            stats.add(c);
        }
        addTo(s, stats, 16);

        String code = Rewards.code(me);
        Round rc = new Round(22, CARD).shadow();
        rc.setBorder(new EmptyBorder(22, 26, 22, 26));
        rc.setLayout(new BorderLayout(0, 12));
        Btn copy = new Btn("Copy code", 0);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
            copy.setText("Copied");
        });
        JPanel codeRow = new JPanel(new BorderLayout());
        codeRow.setOpaque(false);
        codeRow.add(vbox(lbl("YOUR REFERRAL CODE", B, 10, MUTED), gap(4), lbl(code, B, 34, PRIMARY)), BorderLayout.WEST);
        JPanel cw = new JPanel(new GridBagLayout());
        cw.setOpaque(false);
        cw.add(copy);
        codeRow.add(cw, BorderLayout.EAST);
        rc.add(codeRow, BorderLayout.NORTH);
        rc.add(vbox(
                lbl("Share it with a friend: they get " + Rewards.REFEREE_BONUS + " points and you get " + Rewards.REFERRER_BONUS
                        + " points once they complete their first booking.", P, 13, INK), gap(8),
                lbl("Friends who joined with your code: " + Rewards.referrals(me) + "   ·   Rewards paid out: " + Rewards.rewarded(me), B, 13, MUTED)), BorderLayout.CENTER);
        addTo(s, rc, 16);

        boolean booked = false;
        for (Booking b : Bookings.of(me.email)) if (!b.cancelled) booked = true;
        if (me.referredBy == null && !booked) {
            JTextField in = field();
            Btn applyB = new Btn("Apply code", 0);
            applyB.addActionListener(e -> {
                try {
                    Rewards.applyReferral(me, in.getText());
                    ok("Referral code applied. You'll get " + Rewards.REFEREE_BONUS + " bonus points after your first booking.");
                    goRewards();
                } catch (IllegalArgumentException ex) { info(ex.getMessage()); }
            });
            Round ec = new Round(20, CARD).shadow();
            ec.setBorder(new EmptyBorder(18, 22, 20, 22));
            ec.setLayout(new BorderLayout(0, 10));
            ec.add(lbl("HAVE A FRIEND'S CODE?", B, 11, MUTED), BorderLayout.NORTH);
            JPanel er = new JPanel(new BorderLayout(10, 0));
            er.setOpaque(false);
            er.add(in, BorderLayout.CENTER);
            er.add(applyB, BorderLayout.EAST);
            ec.add(er, BorderLayout.CENTER);
            addTo(s, ec, 16);
        } else if (me.referredBy != null) {
            addTo(s, lbl("You joined with a referral code" + (me.refRewarded ? " and your bonus has been paid." : ". Your bonus arrives after your first booking."), P, 13, MUTED), 16);
        }

        Round how = new Round(20, CARD).shadow();
        how.setBorder(new EmptyBorder(18, 24, 20, 24));
        how.setLayout(new BorderLayout(0, 8));
        how.add(lbl("How SkyPoints work", B, 17, INK), BorderLayout.NORTH);
        how.add(vbox(
                wrap("Earn 1 point for every ₹100 you pay. Points count toward your tier: Silver 0+, Gold 200+ (3% off every booking), Platinum 600+ (5% off).", 820, P, 13, MUTED), gap(6),
                wrap("Spend points at checkout: 1 point = ₹1, up to 30% of the amount due. If you cancel, redeemed points are returned in proportion to your refund.", 820, P, 13, MUTED)), BorderLayout.CENTER);
        addTo(s, how, 0);
        show("rewards", pageOf(s));
    }

    // =========================================================
    // REVIEWS & RATINGS
    // =========================================================
    void goReviews() {
        nav(7);
        JPanel s = stack();
        addTo(s, header("Reviews & ratings", "See what travellers say about each airline and share your own experience.", null), 16);

        JPanel grid = new JPanel(new GridLayout(0, 3, 14, 14));
        grid.setOpaque(false);
        for (String al : Reviews.airlines()) {
            Round c = new Round(16, CARD).shadow();
            c.setBorder(new EmptyBorder(14, 18, 14, 18));
            c.setLayout(new BorderLayout());
            int n = Reviews.count(al);
            c.add(vbox(lbl(al, B, 14, INK), gap(6), new Stars(Reviews.avg(al), 16), gap(4),
                    lbl(n == 0 ? "No reviews yet" : String.format(Locale.ENGLISH, "%.1f / 5  ·  %d review%s", Reviews.avg(al), n, n == 1 ? "" : "s"), P, 12, MUTED)));
            grid.add(c);
        }
        addTo(s, grid, 16);

        JComboBox<String> airBox = combo(Reviews.airlines().toArray(new String[0]), 220);
        JTextField flightNo = field();
        flightNo.setToolTipText("Optional, e.g. 6E 123");
        JComboBox<String> starBox = combo(new String[]{"5 - Excellent", "4 - Good", "3 - Average", "2 - Poor", "1 - Terrible"}, 180);
        JTextArea text = new JTextArea(3, 20);
        text.setFont(f(P, 13));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(CARD);
        text.setForeground(INK);
        text.setCaretColor(INK);
        JScrollPane tsp = new JScrollPane(text);
        tsp.setBorder(new CompoundBorder(new RB(new Color(0xCBD5E1), 10), new EmptyBorder(4, 8, 4, 8)));
        tsp.setPreferredSize(new Dimension(100, 80));
        Btn post = new Btn("Post review", 0);

        Round form = new Round(20, CARD).shadow();
        form.setBorder(new EmptyBorder(18, 22, 20, 22));
        form.setLayout(new BorderLayout(0, 10));
        form.add(lbl("WRITE A REVIEW", B, 11, MUTED), BorderLayout.NORTH);
        JPanel fr = new JPanel(new GridLayout(1, 3, 12, 0));
        fr.setOpaque(false);
        fr.add(labeled("Airline", airBox));
        fr.add(labeled("Flight number (optional)", flightNo));
        fr.add(labeled("Rating", starBox));
        form.add(vbox(fr, gap(10), labeled("Your review (max 300 characters)", tsp), gap(10), post), BorderLayout.CENTER);
        addTo(s, form, 16);

        JComboBox<String> filterBox = combo(prepend("All airlines", Reviews.airlines()), 220);
        JPanel list = vlist();
        Runnable refill = () -> {
            String sel = (String) filterBox.getSelectedItem();
            List<Review> l = sel.equals("All airlines") ? Reviews.all() : Reviews.forAirline(sel);
            list.removeAll();
            if (l.isEmpty()) {
                list.add(lbl("No reviews yet. Be the first to write one!", P, 13, MUTED));
            }
            for (Review r : l) {
                Round c = new Round(16, CARD).shadow();
                c.setBorder(new EmptyBorder(14, 18, 14, 18));
                c.setLayout(new BorderLayout(0, 6));
                c.setAlignmentX(0f);
                JPanel top = flow(FlowLayout.LEFT, 10, lbl(r.name, B, 14, INK), new Stars(r.stars, 14),
                        lbl(r.airline + (r.flightNo.isEmpty() ? "" : " · " + r.flightNo), B, 12, PRIMARY));
                if (Reviews.verified(r.email, r.airline)) top.add(pill("VERIFIED FLYER", OK));
                c.add(top, BorderLayout.NORTH);
                if (!r.text.isEmpty()) c.add(wrap(r.text, 780, P, 13, INK), BorderLayout.CENTER);
                c.add(lbl(r.date.format(SD), P, 11, MUTED), BorderLayout.SOUTH);
                list.add(c);
                list.add(gap(8));
            }
            list.revalidate();
            list.repaint();
        };
        filterBox.addActionListener(e -> refill.run());
        post.addActionListener(e -> {
            try {
                Reviews.add(me, (String) airBox.getSelectedItem(), flightNo.getText(), 5 - starBox.getSelectedIndex(), text.getText());
                ok("Thanks! Your review has been saved.");
                goReviews();
            } catch (IllegalArgumentException ex) { info(ex.getMessage()); }
        });
        JPanel lh = new JPanel(new BorderLayout());
        lh.setOpaque(false);
        lh.add(lbl("All reviews", B, 19, INK), BorderLayout.WEST);
        lh.add(filterBox, BorderLayout.EAST);
        addTo(s, lh, 10);
        refill.run();
        addTo(s, list, 0);
        show("reviews", pageOf(s));
    }

    static String[] prepend(String first, List<String> rest) {
        String[] a = new String[rest.size() + 1];
        a[0] = first;
        for (int i = 0; i < rest.size(); i++) a[i + 1] = rest.get(i);
        return a;
    }

    // =========================================================
    // OFFERS & POLICIES
    // =========================================================
    void goOffers() {
        nav(8);
        JPanel s = stack();
        addTo(s, header("Offers & policies", "Promo codes, fare rules, change fees and loyalty perks.", null), 16);

        JPanel row = new JPanel(new GridLayout(1, 3, 14, 0));
        row.setOpaque(false);
        String[][] offers = {
                {"SKY10", "10% off", "Up to ₹1,500 off any booking."},
                {"WELCOME500", "₹500 off", "Valid on your first booking only."},
                {"INTL2000", "₹2,000 off", "International flights, total ₹30,000 or more."}};
        for (String[] o : offers) {
            Round c = new Round(20, CARD).shadow().lift();
            c.setBorder(new EmptyBorder(20, 22, 20, 22));
            c.setLayout(new BorderLayout(0, 14));
            Btn copy = new Btn("Copy code", 2);
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(o[0]), null);
                copy.setText("Copied");
            });
            c.add(vbox(pill(o[0], PRIMARY), gap(10), lbl(o[1], B, 26, INK), gap(4),
                    lbl("<html><div style='width:200px'>" + o[2] + "</div></html>", P, 12, MUTED)), BorderLayout.CENTER);
            c.add(copy, BorderLayout.SOUTH);
            row.add(c);
        }
        addTo(s, row, 18);

        Round pol = new Round(20, CARD).shadow();
        pol.setBorder(new EmptyBorder(20, 24, 22, 24));
        pol.setLayout(new BorderLayout(0, 12));
        pol.add(vbox(lbl("Fare types", B, 18, INK), gap(3),
                lbl("Refunds are calculated on the total paid, based on time before departure. Change fee: ₹750 domestic / ₹2,500 international per traveller, scaled by fare type.", P, 12, MUTED)), BorderLayout.NORTH);
        JPanel g = new JPanel(new GridLayout(1, 3, 12, 0));
        g.setOpaque(false);
        for (FareType ft : FareType.values()) {
            double[] r = ft.refundTiers();
            Round c = new Round(14, CARD2).stroke(LINE);
            c.setBorder(new EmptyBorder(14, 16, 14, 16));
            c.setLayout(new BorderLayout());
            JPanel v = vlist();
            v.add(flow(FlowLayout.LEFT, 8, lbl(ft.label(), B, 17, INK), pill(ft.priceNote().toUpperCase(), PRIMARY)));
            v.add(gap(6));
            for (String perk : ft.perks()) { v.add(lbl("•  " + perk, P, 12, MUTED)); v.add(gap(2)); }
            v.add(gap(6));
            v.add(lbl("48h+  " + Math.round(r[0] * 100) + "%   ·   24–48h  " + Math.round(r[1] * 100) + "%", B, 12, OK));
            v.add(lbl("4–24h  " + Math.round(r[2] * 100) + "%   ·   <4h  " + Math.round(r[3] * 100) + "%", B, 12, r[3] > 0 ? AMBER : DANGER));
            c.add(v);
            g.add(c);
        }
        pol.add(g, BorderLayout.CENTER);
        addTo(s, pol, 18);

        Round seat = new Round(20, CARD).shadow();
        seat.setBorder(new EmptyBorder(18, 24, 20, 24));
        seat.setLayout(new BorderLayout(0, 8));
        seat.add(lbl("Paid seats & points", B, 18, INK), BorderLayout.NORTH);
        seat.add(vbox(
                wrap("Extra-legroom seats (rows 1–3): ₹800 domestic / ₹2,000 international. Exit-row seats (rows 8–9): ₹600 domestic / ₹1,500 international. Free in Business; Premium fares get 50% off.", 900, P, 13, MUTED), gap(6),
                wrap("SkyPoints: earn 1 per ₹100, spend 1 = ₹1 (up to 30% of the amount). Gold gets 3% off every booking and Platinum 5%. Refer a friend: they get 150 points, you get 250.", 900, P, 13, MUTED)), BorderLayout.CENTER);
        addTo(s, seat, 0);

        show("offers", pageOf(s));
    }

    // =========================================================
    // PROFILE
    // =========================================================
    void goProfile() {
        nav(9);
        List<Booking> all = Bookings.of(me.email);
        JPanel s = stack();
        addTo(s, header("My profile", "Your account details and travel summary.", null), 16);

        Round card = new Round(22, CARD).shadow();
        card.setBorder(new EmptyBorder(24, 26, 26, 26));
        card.setLayout(new BorderLayout(0, 20));

        JPanel id = new JPanel(new BorderLayout(18, 0));
        id.setOpaque(false);
        id.add(new Avatar(initials(me.name), 72), BorderLayout.WEST);
        id.add(vbox(lbl(me.name, B, 24, INK), gap(3), lbl("SkyIndia member since " + me.joined.format(SD), P, 13, MUTED), gap(8),
                pill(Loyalty.of(me.email).name().toUpperCase() + " MEMBER", tierColor(Loyalty.of(me.email).name()))), BorderLayout.CENTER);
        card.add(id, BorderLayout.NORTH);

        JPanel g = new JPanel(new GridLayout(2, 3, 18, 16));
        g.setOpaque(false);
        long trips = all.stream().filter(b -> !b.cancelled).count();
        g.add(detail("FULL NAME", me.name));
        g.add(detail("EMAIL", me.email));
        g.add(detail("MOBILE", me.phone));
        g.add(detail("TRIPS BOOKED", String.valueOf(trips)));
        g.add(detail("TOTAL SAVED", inr(all.stream().mapToDouble(b -> b.discount).sum())));
        g.add(detail("TOTAL SPENT", inr(all.stream().filter(b -> !b.cancelled).mapToDouble(b -> b.total).sum())));
        card.add(g, BorderLayout.CENTER);

        Btn edit = new Btn("Edit profile", 2);
        edit.addActionListener(e -> {
            JTextField n = field(), ph = field();
            n.setText(me.name);
            ph.setText(me.phone);
            JPanel fp = vbox(labeled("Full name", n), gap(8), labeled("Mobile (10 digits)", ph));
            fp.setPreferredSize(new Dimension(320, 130));
            if (JOptionPane.showConfirmDialog(this, fp, "Edit profile", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                try { Auth.updateProfile(me, n.getText(), ph.getText()); showShell(); goProfile(); }
                catch (IllegalArgumentException ex) { info(ex.getMessage()); }
            }
        });
        Btn pw = new Btn("Change password", 2);
        pw.addActionListener(e -> {
            JPasswordField o = new JPasswordField(), n1 = new JPasswordField(), n2 = new JPasswordField();
            styleField(o); styleField(n1); styleField(n2);
            JPanel fp = vbox(labeled("Current password", o), gap(8), labeled("New password", n1), gap(8), labeled("Repeat new password", n2));
            fp.setPreferredSize(new Dimension(320, 230));
            if (JOptionPane.showConfirmDialog(this, fp, "Change password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                String a = new String(n1.getPassword()), b = new String(n2.getPassword());
                if (!a.equals(b)) { info("The new passwords do not match."); return; }
                try { Auth.changePassword(me, new String(o.getPassword()), a); ok("Password changed."); }
                catch (IllegalArgumentException ex) { info(ex.getMessage()); }
            }
        });
        Btn out = new Btn("Log out", 5);
        out.addActionListener(e -> { me = null; navs = null; filter = "All"; showLogin(); });
        card.add(flow(FlowLayout.LEFT, 8, edit, pw, out), BorderLayout.SOUTH);
        addTo(s, card, 16);
        addTo(s, Extras.loyalty(me, this::goRewards), 16);
        addTo(s, Extras.analytics(all), 0);

        show("profile", pageOf(s));
    }

    static Color tierColor(String tier) { return Extras.tierColor(tier); }

    // =========================================================
    // HELP & SUPPORT
    // =========================================================
    void goHelp() {
        nav(10);
        show("help", Extras.helpPage());
    }

    // =========================================================
    // MAIN
    // =========================================================
    static void cleanLegacyDemoDataOnce() {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".skyindia");
            Path marker = dir.resolve(".skyindia_v2_clean");
            Path data = dir.resolve("data.ser");
            if (!Files.exists(marker)) {
                Files.createDirectories(dir);
                Files.deleteIfExists(data);
                Files.writeString(marker, "cleaned");
            }
        } catch (Exception ignored) {
            // The application can still start normally if local cleanup is unavailable.
        }
    }

    public static void main(String[] args) {
        cleanLegacyDemoDataOnce();
        try {
            for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
                if (laf.getName().equals("Nimbus")) {
                    UIManager.setLookAndFeel(laf.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new SkyIndia().setVisible(true));
    }
}