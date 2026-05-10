package scoundrel;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Scoundrel ampliado.
 * Mazo: 9 corazones (2-10) + 9 diamantes (2-10) + 13 picas (2-A) + 13 tréboles (2-A)
 *       + 4 figuras rojas (J,Q,K,A de corazones y diamantes)*2 = 8 + 2 jokers = 54 cartas.
 * Reglas adicionales:
 *  - Vampiros (J negras): si se vencen a mano, vuelven al fondo del mazo.
 *  - Brujas (Q negras): mientras estén en la sala, las pociones envenenan (dañan en lugar de curar).
 *  - Liches (K negras): los aliados (heroe/doncella/tomo) en la misma sala mueren al ser revelados.
 *  - Dragones (A negras): hacen 1d6 de fuego al colocarse en la sala. Si quedan como sobrante, repiten.
 *  - Herreros (J rojas): pueden Reparar (quitar el monstruo superior de la pila del arma)
 *      o Mejorar (+1 daño al arma, sólo en arma sin daño).
 *  - Doncellas (Q rojas): se reclutan, pueden guardar 1 arma o poción. Recoger su objeto la descarta.
 *  - Héroes (K rojas): se reclutan, mitigan 1d6 de daño y se descartan tras la tirada.
 *  - Tomos de Fuego (A rojas): arma mágica que destruye TODOS los monstruos de la sala. Un sólo uso.
 *  - Mercaderes (Jokers): compran tu arma o el arma de la doncella por una poción de valor =
 *      menor valor de la pila del arma. El tomo se vende por una poción de 10. Tras 1 trato se descarta.
 */
public class Scoundrel {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String imgDir = args.length > 0 ? args[0] : findImagesDir();
            JFrame f = new JFrame("Scoundrel");
            GamePanel panel = new GamePanel(imgDir);
            f.setContentPane(panel);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(1366, 860);
            f.setMinimumSize(new Dimension(1180, 760));
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            panel.startGame();
        });
    }

    static String findImagesDir() {
        String[] candidates = {".", "..", "../..", "cards", "../cards",
                System.getProperty("user.dir"),
                System.getProperty("user.dir") + "/.."};
        for (String c : candidates) {
            File f = new File(c, "Dungeon.png");
            if (f.exists()) return new File(c).getAbsolutePath();
        }
        return ".";
    }
}

/* ================== MODELO ================== */

enum Suit {
    CORAZONES("corazones"), DIAMANTES("diamantes"),
    PICAS("picas"), TREBOL("trebol"), JOKER("joker");
    final String key;
    Suit(String k) { this.key = k; }
    boolean isBlack() { return this == PICAS || this == TREBOL; }
    boolean isRed()   { return this == CORAZONES || this == DIAMANTES; }
}

enum CardType {
    MONSTER, WEAPON, POTION,
    VAMPIRE, HAG, LICH, DRAGON,
    BLACKSMITH, MAIDEN, HERO, TOME,
    MERCHANT
}

class Card {
    final Suit suit;
    final int value;
    final int id;
    Card(Suit s, int v, int id) { this.suit = s; this.value = v; this.id = id; }

    CardType type() {
        if (suit == Suit.JOKER) return CardType.MERCHANT;
        if (value <= 10) {
            if (suit.isBlack()) return CardType.MONSTER;
            if (suit == Suit.DIAMANTES) return CardType.WEAPON;
            return CardType.POTION;
        }
        boolean black = suit.isBlack();
        if (value == 11) return black ? CardType.VAMPIRE : CardType.BLACKSMITH;
        if (value == 12) return black ? CardType.HAG     : CardType.MAIDEN;
        if (value == 13) return black ? CardType.LICH    : CardType.HERO;
        return black ? CardType.DRAGON : CardType.TOME;
    }
    boolean isMonster() {
        CardType t = type();
        return t == CardType.MONSTER || t == CardType.VAMPIRE || t == CardType.HAG
            || t == CardType.LICH || t == CardType.DRAGON;
    }
    boolean isAlly() {
        CardType t = type();
        return t == CardType.HERO || t == CardType.MAIDEN || t == CardType.TOME;
    }
    String label() {
        if (suit == Suit.JOKER) return "Mercader";
        String n;
        switch (value) {
            case 11: n = "J"; break;
            case 12: n = "Q"; break;
            case 13: n = "K"; break;
            case 14: n = "A"; break;
            default: n = String.valueOf(value);
        }
        String s;
        switch (suit) {
            case CORAZONES: s = "♥"; break;
            case DIAMANTES: s = "♦"; break;
            case PICAS:     s = "♠"; break;
            default:        s = "♣";
        }
        return n + s;
    }
    @Override public String toString() { return label(); }
}

class GameModel {
    static final int MAX_HP = 20;
    Deque<Card> deck = new ArrayDeque<>();
    List<Card> room = new ArrayList<>();
    int hp = MAX_HP;
    Card weapon = null;
    int weaponBonus = 0;            // bonus por mejora de herrero
    boolean weaponUpgraded = false; // herrero colocado encima
    List<Card> weaponStackCards = new ArrayList<>(); // monstruos vencidos (modelo)
    int lastSlain = Integer.MAX_VALUE;
    boolean potionUsedThisRoom = false;
    boolean lastRoomSkipped = false;
    int roomCounter = 0;
    boolean gameOver = false;
    boolean won = false;

    // Aliados / objetos
    Card maiden = null;
    Card maidenItem = null;     // arma o poción que sostiene la doncella
    int heroes = 0;
    Card tome = null;           // tomo de fuego en mano

    String message = "Bienvenido a la mazmorra.";
    String diceMessage = "";
    long diceMessageUntil = 0;

    int rollD6() {
        int r = 1 + (int)(Math.random() * 6);
        diceMessage = "🎲 d6 = " + r;
        diceMessageUntil = System.currentTimeMillis() + 2200;
        return r;
    }

    void newGame() {
        deck.clear(); room.clear();
        weaponStackCards.clear();
        hp = MAX_HP; weapon = null; weaponBonus = 0; weaponUpgraded = false;
        lastSlain = Integer.MAX_VALUE;
        potionUsedThisRoom = false; lastRoomSkipped = false;
        gameOver = false; won = false; roomCounter = 0;
        maiden = null; maidenItem = null; heroes = 0; tome = null;
        diceMessage = ""; diceMessageUntil = 0;
        message = "Adéntrate en la mazmorra...";
        List<Card> all = new ArrayList<>();
        int idc = 1;
        for (int v = 2; v <= 10; v++) all.add(new Card(Suit.CORAZONES, v, idc++));
        for (int v = 2; v <= 10; v++) all.add(new Card(Suit.DIAMANTES, v, idc++));
        for (int v = 2; v <= 14; v++) all.add(new Card(Suit.PICAS,    v, idc++));
        for (int v = 2; v <= 14; v++) all.add(new Card(Suit.TREBOL,   v, idc++));
        for (int v = 11; v <= 14; v++) all.add(new Card(Suit.CORAZONES, v, idc++));
        for (int v = 11; v <= 14; v++) all.add(new Card(Suit.DIAMANTES, v, idc++));
        all.add(new Card(Suit.JOKER, 15, idc++));
        all.add(new Card(Suit.JOKER, 15, idc++));
        Collections.shuffle(all);
        for (Card c : all) deck.addLast(c);
    }

    int dealRoom() {
        int added = 0;
        while (room.size() < 4 && !deck.isEmpty()) {
            room.add(deck.pollFirst());
            added++;
        }
        if (added > 0) roomCounter++;
        potionUsedThisRoom = false;
        // Daño de fuego de cada dragón presente en la sala
        for (Card c : new ArrayList<>(room)) {
            if (c.type() == CardType.DRAGON) {
                int r = rollD6();
                int real = applyDamage(r, "Dragón " + c.label() + " escupe fuego");
                message = "Dragón escupe fuego (1d6=" + r + ", -" + real + " HP).";
                if (gameOver) return added;
            }
        }
        return added;
    }

    boolean canSkip() {
        return !lastRoomSkipped && room.size() == 4 && !gameOver;
    }

    boolean skip() {
        if (!canSkip()) return false;
        List<Card> shuffled = new ArrayList<>(room);
        Collections.shuffle(shuffled);
        for (Card c : shuffled) deck.addLast(c);
        room.clear();
        lastRoomSkipped = true;
        message = "Saltas la sala.";
        return true;
    }

    /** Aplica daño con mitigación de héroes. Devuelve daño real. */
    int applyDamage(int dmg, String source) {
        StringBuilder sb = new StringBuilder();
        while (heroes > 0 && dmg > 0) {
            int r = rollD6();
            int mit = Math.min(dmg, r);
            dmg -= mit;
            heroes--;
            sb.append(" Héroe mitiga ").append(mit).append(".");
        }
        hp -= dmg;
        if (sb.length() > 0) message = source + "." + sb.toString() + " (-" + dmg + " HP)";
        if (hp <= 0) { hp = 0; gameOver = true; won = false; message = "Has muerto."; }
        return dmg;
    }

    /** Combate contra un monstruo. */
    void fight(Card monster, boolean withWeapon) {
        int dmg;
        boolean usedWeapon = false;
        if (withWeapon && weapon != null && monster.value < lastSlain) {
            int wv = weapon.value + weaponBonus;
            dmg = Math.max(0, monster.value - wv);
            lastSlain = monster.value;
            usedWeapon = true;
            weaponStackCards.add(monster);
            // tras 1 monstruo, el arma se considera "dañada" -> mejora aún cuenta pero ya no se puede mejorar
            message = "Atacas al " + monster.label() + " con " + weapon.label()
                      + (weaponBonus > 0 ? "+" + weaponBonus : "") + ". -" + dmg + " HP.";
        } else {
            dmg = monster.value;
            if (withWeapon && weapon != null && monster.value >= lastSlain) {
                message = "El arma no sirve contra ≥ " + lastSlain + ". A mano. -" + dmg + " HP.";
            } else {
                message = "Combates al " + monster.label() + " a mano. -" + dmg + " HP.";
            }
        }
        applyDamage(dmg, "Combate");
        // Vampiro vencido a mano: vuelve al fondo del mazo
        if (monster.type() == CardType.VAMPIRE && !usedWeapon) {
            deck.addLast(monster);
            message += " El vampiro regenera al fondo del mazo.";
        }
    }

    /** Usado para devolver una carta al descarte (lógica del modelo no necesita lista de descarte). */
    void discard(Card c) { /* no-op (la vista lleva la pila) */ }

    /** Comprueba fin de juego tras resolver una carta. */
    void postResolve() {
        if (hp <= 0) { hp = 0; gameOver = true; won = false; message = "Has muerto."; return; }
        if (deck.isEmpty() && room.isEmpty()) {
            gameOver = true; won = true;
            message = "¡Victoria! Has limpiado la mazmorra con " + hp + " HP.";
            return;
        }
        if ((room.size() <= 1) && (room.size() + deck.size() < 4)) {
            gameOver = true; won = true;
            message = "¡Victoria! El mazo no puede formar otra sala. HP: " + hp;
            return;
        }
        if (room.size() == 1 && !deck.isEmpty()) {
            lastRoomSkipped = false;
            dealRoom();
        } else if (room.isEmpty() && !deck.isEmpty()) {
            lastRoomSkipped = false;
            dealRoom();
        }
    }

    /** Retorna lista de menor a mayor: cartas del arma (incluye el arma misma + monstruos). */
    int weaponStackLowest() {
        if (weapon == null) return 0;
        int min = weapon.value;
        for (Card c : weaponStackCards) if (c.value < min) min = c.value;
        return min;
    }

    boolean weaponUndamaged() {
        return weapon != null && weaponStackCards.isEmpty();
    }

    boolean hagInRoom() {
        for (Card c : room) if (c.type() == CardType.HAG) return true;
        return false;
    }

    boolean lichInRoom() {
        for (Card c : room) if (c.type() == CardType.LICH) return true;
        return false;
    }
}

/* ================== VISTA: SPRITE ================== */

class CardSprite {
    Card card;
    double x, y, tx, ty;
    double rotY = Math.PI, trotY = 0;
    double scale = 1.0, tscale = 1.0;
    double lift = 0, tlift = 0;
    double opacity = 1.0, topacity = 1.0;
    double idleT = Math.random() * Math.PI * 2;
    int w, h;
    BufferedImage front, back;

    CardSprite(Card c, BufferedImage front, BufferedImage back, int w, int h) {
        this.card = c; this.front = front; this.back = back; this.w = w; this.h = h;
    }

    void update(double dt) {
        idleT += dt;
        double k = Math.min(1, dt * 7.0);
        x += (tx - x) * k;
        y += (ty - y) * k;
        rotY += (trotY - rotY) * k;
        scale += (tscale - scale) * k;
        lift += (tlift - lift) * Math.min(1, dt * 12);
        opacity += (topacity - opacity) * Math.min(1, dt * 5);
    }

    void draw(Graphics2D g) {
        AffineTransform old = g.getTransform();
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                (float)Math.max(0, Math.min(1, opacity))));
        double cx = x + w / 2.0;
        double cy = y + h / 2.0 + Math.sin(idleT * 1.4) * 1.5 - lift;
        double shadowAlpha = 0.45 * Math.max(0, 1 - lift / 80.0);
        g.setColor(new Color(0, 0, 0, (int)(180 * shadowAlpha * opacity)));
        int sw = (int)(w * (0.7 + lift / 200.0));
        int sh = (int)(14 + lift / 4.0);
        g.fillOval((int)(cx - sw / 2), (int)(cy + h / 2.0 - 6 + lift * 0.2), sw, sh);
        g.translate(cx, cy);
        double sx = Math.cos(rotY);
        double absSx = Math.max(0.0001, Math.abs(sx));
        AffineTransform t = new AffineTransform();
        t.scale(absSx * scale, scale);
        t.shear(0, Math.sin(rotY) * 0.06);
        g.transform(t);
        BufferedImage img = (sx >= 0) ? front : back;
        if (img != null) g.drawImage(img, -w / 2, -h / 2, w, h, null);
        else {
            g.setColor(new Color(40, 40, 60));
            g.fillRoundRect(-w / 2, -h / 2, w, h, 22, 22);
        }
        g.setColor(new Color(255, 220, 140, 90));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(-w / 2, -h / 2, w - 1, h - 1, 18, 18);
        g.setTransform(old);
        g.setComposite(oc);
    }

    boolean contains(int px, int py) {
        return px >= x && px <= x + w && py >= y - lift && py <= y + h - lift;
    }
}

/* ================== VISTA: PANEL ================== */

class GamePanel extends JPanel implements MouseMotionListener, MouseListener {
    static final int CARD_W = 160;
    static final int CARD_H = 226;

    final String imgDir;
    BufferedImage bg;
    BufferedImage[] bgPool = new BufferedImage[0];
    BufferedImage bgNext = null;
    double bgFadeT = 1.0;
    int lastRoomCounter = -1;
    BufferedImage bgVictoria, bgDerrota;
    BufferedImage cardBack;
    BufferedImage damageIcon;
    Map<Integer, BufferedImage> imgCache = new HashMap<>();

    GameModel model = new GameModel();
    List<CardSprite> sprites = new ArrayList<>();
    CardSprite weaponSprite;
    List<CardSprite> weaponStack = new ArrayList<>();
    List<CardSprite> discardPile = new ArrayList<>();
    CardSprite maidenSprite, maidenItemSprite, tomeSprite;
    Timer ticker;
    long lastNanos;
    CardSprite hovered;

    JButton btnSkip;
    JButton btnNew;
    JLabel statusLabel;

    int deckX, deckY;
    int discardX, discardY;
    int weaponX, weaponY;
    int partyX, partyY;

    GamePanel(String imgDir) {
        this.imgDir = imgDir;
        setLayout(null);
        setBackground(new Color(15, 12, 18));
        loadAssets();

        statusLabel = new JLabel("Cargando...");
        statusLabel.setForeground(new Color(240, 230, 200));
        statusLabel.setFont(new Font("Serif", Font.ITALIC | Font.BOLD, 17));
        add(statusLabel);

        btnSkip = stylized("Saltar sala");
        btnSkip.addActionListener(e -> doSkip());
        add(btnSkip);

        btnNew = stylized("Nueva partida");
        btnNew.addActionListener(e -> startGame());
        add(btnNew);

        addMouseListener(this);
        addMouseMotionListener(this);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { layoutSlots(); }
        });
    }

    JButton stylized(String txt) {
        JButton b = new JButton(txt) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? new Color(120, 80, 30) : new Color(70, 45, 20));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(new Color(220, 180, 110));
                g2.setStroke(new BasicStroke(2));
                g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 14, 14);
                g2.setFont(new Font("Serif", Font.BOLD, 16));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(new Color(245, 230, 200));
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 2);
                g2.dispose();
            }
        };
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        return b;
    }

    void loadAssets() {
        java.util.List<BufferedImage> pool = new ArrayList<>();
        for (String n : new String[]{"Dungeon.png","Dungeon1.png","Dungeon2.png","Dungeon3.png","Dungeon4.png","Dungeon5.png","Dungeon6.png"}) {
            File f = new File(imgDir, n);
            if (f.exists()) {
                try { BufferedImage im = ImageIO.read(f); if (im != null) pool.add(im); } catch (Exception ignored) {}
            }
        }
        bgPool = pool.toArray(new BufferedImage[0]);
        if (bgPool.length > 0) bg = bgPool[(int)(Math.random() * bgPool.length)];
        try {
            for (String n : new String[]{"daño.png","dano.png","damage.png"}) {
                File f = new File(imgDir, n);
                if (f.exists()) { damageIcon = ImageIO.read(f); break; }
            }
        } catch (Exception ignored) {}
        try { File f = new File(imgDir, "victoria.png"); if (f.exists()) bgVictoria = ImageIO.read(f); } catch (Exception ignored) {}
        try { File f = new File(imgDir, "derrota.png");  if (f.exists()) bgDerrota  = ImageIO.read(f); } catch (Exception ignored) {}
        cardBack = makeCardBack();
    }

    BufferedImage makeCardBack() {
        BufferedImage img = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        GradientPaint gp = new GradientPaint(0, 0, new Color(80, 30, 30), CARD_W, CARD_H, new Color(35, 10, 10));
        g.setPaint(gp); g.fillRoundRect(0, 0, CARD_W - 1, CARD_H - 1, 18, 18);
        g.setColor(new Color(220, 180, 110));
        g.setStroke(new BasicStroke(3));
        g.drawRoundRect(6, 6, CARD_W - 13, CARD_H - 13, 14, 14);
        g.setColor(new Color(180, 140, 90, 180));
        for (int i = 0; i < 6; i++) {
            int s = 14 + i * 14;
            g.drawRoundRect(CARD_W / 2 - s, CARD_H / 2 - s, s * 2, s * 2, 8, 8);
        }
        g.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 36));
        g.setColor(new Color(240, 210, 150));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("S", (CARD_W - fm.stringWidth("S")) / 2, CARD_H / 2 + 14);
        g.dispose();
        return img;
    }

    BufferedImage loadCardImage(Card c) {
        if (imgCache.containsKey(c.id)) return imgCache.get(c.id);
        String[] candidates = filenameCandidates(c);
        BufferedImage img = null;
        for (String name : candidates) {
            File f = new File(imgDir, name);
            if (f.exists()) {
                try {
                    BufferedImage raw = ImageIO.read(f);
                    if (raw != null) { img = raw; break; }
                } catch (Exception ignored) {}
            }
        }
        if (img != null) {
            BufferedImage scaled = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, CARD_W, CARD_H, 18, 18));
            g.drawImage(img, 0, 0, CARD_W, CARD_H, null);
            g.dispose();
            img = scaled;
        } else {
            img = makeFallback(c);
        }
        imgCache.put(c.id, img);
        return img;
    }

    String[] filenameCandidates(Card c) {
        if (c.suit == Suit.JOKER) {
            // Joker1.png o Joker2.png según id (los dos jokers se distinguen por id)
            int variant = (c.id % 2 == 0) ? 2 : 1;
            return new String[] { "Joker" + variant + ".png", "Joker.png", "joker.png" };
        }
        String num;
        switch (c.value) {
            case 11: num = "Jota"; break;
            case 12: num = "Reina"; break;
            case 13: num = "Rey"; break;
            case 14: num = "As"; break;
            default: num = String.valueOf(c.value);
        }
        String suit;
        switch (c.suit) {
            case CORAZONES: suit = "corazones"; break;
            case DIAMANTES: suit = "diamantes"; break;
            case PICAS:     suit = "picas"; break;
            default:        suit = "trebol";
        }
        // Variantes con/sin tilde y con mayúscula inicial
        java.util.List<String> v = new ArrayList<>();
        String[] suitForms;
        if (c.suit == Suit.TREBOL) suitForms = new String[]{"trebol","Trebol","trébol","Trébol"};
        else if (c.suit == Suit.CORAZONES) suitForms = new String[]{"corazones","Corazones"};
        else if (c.suit == Suit.DIAMANTES) suitForms = new String[]{"diamantes","Diamantes"};
        else suitForms = new String[]{"picas","Picas"};
        String[] numForms;
        if (c.value >= 11) {
            String low = num.toLowerCase();
            String cap = num;
            numForms = new String[]{cap, low};
        } else {
            numForms = new String[]{num};
        }
        for (String n : numForms)
            for (String s : suitForms)
                v.add(n + " de " + s + ".png");
        return v.toArray(new String[0]);
    }

    BufferedImage makeFallback(Card c) {
        BufferedImage img = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color base;
        switch (c.type()) {
            case POTION: case MAIDEN: base = new Color(160, 40, 40); break;
            case WEAPON: case BLACKSMITH: base = new Color(180, 130, 40); break;
            case TOME:   base = new Color(200, 80, 40); break;
            case HERO:   base = new Color(220, 180, 90); break;
            case MERCHANT: base = new Color(120, 60, 130); break;
            case DRAGON: base = new Color(180, 40, 30); break;
            case LICH:   base = new Color(60, 20, 80); break;
            case HAG:    base = new Color(40, 80, 50); break;
            case VAMPIRE: base = new Color(100, 20, 30); break;
            default:     base = new Color(40, 40, 60);
        }
        g.setPaint(new GradientPaint(0, 0, base.brighter(), 0, CARD_H, base.darker()));
        g.fillRoundRect(0, 0, CARD_W - 1, CARD_H - 1, 18, 18);
        g.setColor(new Color(255, 240, 200));
        g.setFont(new Font("Serif", Font.BOLD, 32));
        FontMetrics fm = g.getFontMetrics();
        String s = c.label();
        g.drawString(s, (CARD_W - fm.stringWidth(s)) / 2, CARD_H / 2 - 10);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        FontMetrics fm2 = g.getFontMetrics();
        String t = c.type().toString();
        g.drawString(t, (CARD_W - fm2.stringWidth(t)) / 2, CARD_H / 2 + 18);
        g.dispose();
        return img;
    }

    void layoutSlots() {
        int W = getWidth();
        int H = getHeight();
        deckX = W - CARD_W - 30;
        deckY = 110;
        discardX = 30;
        discardY = 110;
        weaponX = 30;
        weaponY = H - CARD_H - 70;
        partyX = W - CARD_W - 30;
        partyY = H - CARD_H - 70;
        statusLabel.setBounds(20, H - 36, W - 40, 24);
        btnSkip.setBounds(W - 380, 20, 160, 40);
        btnNew.setBounds(W - 200, 20, 170, 40);
    }

    void startGame() {
        layoutSlots();
        sprites.clear();
        weaponStack.clear();
        discardPile.clear();
        weaponSprite = null;
        maidenSprite = null; maidenItemSprite = null; tomeSprite = null;
        lastRoomCounter = -1; bgNext = null; bgFadeT = 1.0;
        model.newGame();
        for (Card c : model.deck) {
            CardSprite s = new CardSprite(c, loadCardImage(c), cardBack, CARD_W, CARD_H);
            s.x = s.tx = deckX;
            s.y = s.ty = deckY;
            s.rotY = s.trotY = Math.PI;
            sprites.add(s);
        }
        hovered = null;
        model.dealRoom();
        repositionRoom();
        if (ticker != null) ticker.stop();
        lastNanos = System.nanoTime();
        ticker = new Timer(16, e -> {
            long now = System.nanoTime();
            double dt = (now - lastNanos) / 1e9;
            lastNanos = now;
            if (dt > 0.05) dt = 0.05;
            for (CardSprite s : sprites) s.update(dt);
            for (CardSprite s : weaponStack) s.update(dt);
            for (CardSprite s : discardPile) s.update(dt);
            if (weaponSprite != null) weaponSprite.update(dt);
            if (maidenSprite != null) maidenSprite.update(dt);
            if (maidenItemSprite != null) maidenItemSprite.update(dt);
            if (tomeSprite != null) tomeSprite.update(dt);
            updateBackground(dt);
            statusLabel.setText(model.message);
            btnSkip.setEnabled(model.canSkip());
            repaint();
        });
        ticker.start();
    }

    void updateBackground(double dt) {
        if (!model.gameOver && model.roomCounter != lastRoomCounter && bgPool.length > 0) {
            lastRoomCounter = model.roomCounter;
            BufferedImage cand = bg;
            if (bgPool.length > 1) {
                int tries = 0;
                while ((cand == bg || cand == null) && tries++ < 20) {
                    cand = bgPool[(int)(Math.random() * bgPool.length)];
                }
            } else cand = bgPool[0];
            if (cand != bg) { bgNext = cand; bgFadeT = 0; }
        }
        if (bgNext != null && bgFadeT < 1.0) {
            bgFadeT = Math.min(1.0, bgFadeT + dt * 0.7);
            if (bgFadeT >= 1.0) { bg = bgNext; bgNext = null; }
        }
    }

    void repositionRoom() {
        int W = getWidth();
        int spacing = 26;
        int total = 4 * CARD_W + 3 * spacing;
        int startX = (W - total) / 2;
        int y = (getHeight() - CARD_H) / 2 - 20;
        for (int i = 0; i < model.room.size(); i++) {
            Card c = model.room.get(i);
            CardSprite s = findSprite(c);
            if (s == null) continue;
            s.tx = startX + i * (CARD_W + spacing);
            s.ty = y;
            s.trotY = 0;
            s.tscale = 1.0;
            s.topacity = 1.0;
        }
    }

    CardSprite findSprite(Card c) {
        for (CardSprite s : sprites) if (s.card == c) return s;
        return null;
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        drawBgCover(g, bg, 1f);
        if (bgNext != null) drawBgCover(g, bgNext, (float) bgFadeT);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(0, 0, getWidth(), getHeight());

        drawHud(g);
        drawSlot(g, discardX, discardY, "Descarte");
        // mazo
        int remain = model.deck.size();
        for (int i = Math.min(8, remain) - 1; i >= 0; i--) {
            int ox = deckX + i;
            int oy = deckY + i;
            g.setColor(new Color(0, 0, 0, 100));
            g.fillRoundRect(ox + 4, oy + 6, CARD_W, CARD_H, 18, 18);
            g.drawImage(cardBack, ox, oy, CARD_W, CARD_H, null);
        }
        g.setFont(new Font("Serif", Font.BOLD, 17));
        g.setColor(new Color(255, 230, 180));
        String dt = "Mazo: " + remain;
        g.drawString(dt, deckX + (CARD_W - g.getFontMetrics().stringWidth(dt)) / 2, deckY + CARD_H + 22);

        drawWeapon(g);
        drawParty(g);

        // Sala
        java.util.List<CardSprite> toDraw = new ArrayList<>();
        for (Card c : model.room) { CardSprite s = findSprite(c); if (s != null) toDraw.add(s); }
        toDraw.sort((a, b) -> { if (a == hovered) return 1; if (b == hovered) return -1; return 0; });
        for (CardSprite s : toDraw) s.draw(g);

        for (Card c : model.room) {
            CardSprite s = findSprite(c);
            if (s != null) { drawCardInfo(g, s); drawDamageButton(g, s); }
        }

        for (CardSprite s : weaponStack) s.draw(g);
        for (CardSprite s : discardPile) if (s.opacity > 0.02) s.draw(g);

        // Aviso de tirada de dado
        if (System.currentTimeMillis() < model.diceMessageUntil) {
            g.setFont(new Font("Serif", Font.BOLD, 22));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(model.diceMessage) + 30;
            int x = (getWidth() - w) / 2;
            int y = 90;
            g.setColor(new Color(0, 0, 0, 200));
            g.fillRoundRect(x, y, w, 40, 12, 12);
            g.setColor(new Color(255, 220, 130));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(x, y, w, 40, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(model.diceMessage, x + 15, y + 28);
        }

        if (model.gameOver) drawEndOverlay(g);
        g.dispose();
    }

    void drawEndOverlay(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, getWidth(), getHeight());
        BufferedImage end = model.won ? bgVictoria : bgDerrota;
        int cardW = 360, cardH = 504;
        int cx = getWidth() / 2;
        int cy = getHeight() / 2 - 40;
        int cardX = cx - cardW / 2, cardY = cy - cardH / 2;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRoundRect(cardX + 10, cardY + 16, cardW, cardH, 24, 24);
        if (end != null) {
            Shape clip = g.getClip();
            g.setClip(new java.awt.geom.RoundRectangle2D.Float(cardX, cardY, cardW, cardH, 22, 22));
            double sx = (double) cardW / end.getWidth();
            double sy = (double) cardH / end.getHeight();
            double sc = Math.max(sx, sy);
            int dw = (int)(end.getWidth() * sc);
            int dh = (int)(end.getHeight() * sc);
            g.drawImage(end, cardX + (cardW - dw) / 2, cardY + (cardH - dh) / 2, dw, dh, null);
            g.setClip(clip);
        } else {
            g.setColor(model.won ? new Color(80, 60, 20) : new Color(60, 10, 10));
            g.fillRoundRect(cardX, cardY, cardW, cardH, 22, 22);
        }
        g.setColor(model.won ? new Color(240, 200, 110) : new Color(200, 60, 60));
        g.setStroke(new BasicStroke(4));
        g.drawRoundRect(cardX, cardY, cardW, cardH, 22, 22);

        String msg = model.won ? "¡VICTORIA!" : "DERROTA";
        String sub = model.won ? ("HP final: " + model.hp) : "Pulsa “Nueva partida” para reintentar.";
        Font fMsg = new Font("Serif", Font.BOLD, 48);
        Font fSub = new Font("Serif", Font.PLAIN, 20);
        FontMetrics fmM = g.getFontMetrics(fMsg);
        FontMetrics fmS = g.getFontMetrics(fSub);
        int textW = Math.max(fmM.stringWidth(msg), fmS.stringWidth(sub)) + 60;
        int boxX = cx - textW / 2;
        int boxY = cardY + cardH + 18;
        g.setColor(new Color(0, 0, 0, 235));
        g.fillRoundRect(boxX, boxY, textW, 110, 16, 16);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(boxX, boxY, textW, 110, 16, 16);
        g.setFont(fMsg);
        g.drawString(msg, cx - fmM.stringWidth(msg) / 2, boxY + 52);
        g.setFont(fSub);
        g.drawString(sub, cx - fmS.stringWidth(sub) / 2, boxY + 88);
    }

    void drawBgCover(Graphics2D g, BufferedImage img, float alpha) {
        if (img == null) return;
        Composite oc = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, alpha))));
        double sx = (double) getWidth() / img.getWidth();
        double sy = (double) getHeight() / img.getHeight();
        double sc = Math.max(sx, sy);
        int dw = (int) (img.getWidth() * sc);
        int dh = (int) (img.getHeight() * sc);
        int dx = (getWidth() - dw) / 2;
        int dy = (getHeight() - dh) / 2;
        g.drawImage(img, dx, dy, dw, dh, null);
        g.setComposite(oc);
    }

    void drawHud(Graphics2D g) {
        g.setColor(new Color(20, 14, 10, 200));
        g.fillRoundRect(20, 14, 380, 60, 14, 14);
        g.setColor(new Color(220, 180, 110));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(20, 14, 380, 60, 14, 14);
        g.setFont(new Font("Serif", Font.BOLD, 22));
        g.setColor(new Color(240, 220, 170));
        g.drawString("Salud", 36, 40);
        int barX = 36, barY = 50, barW = 340, barH = 16;
        g.setColor(new Color(60, 20, 20));
        g.fillRoundRect(barX, barY, barW, barH, 8, 8);
        double frac = model.hp / (double) GameModel.MAX_HP;
        g.setPaint(new GradientPaint(barX, barY, new Color(220, 60, 60), barX + barW, barY, new Color(255, 180, 80)));
        g.fillRoundRect(barX, barY, (int) (barW * frac), barH, 8, 8);
        g.setColor(new Color(220, 180, 110));
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(barX, barY, barW, barH, 8, 8);
        g.setFont(new Font("Serif", Font.BOLD, 14));
        g.setColor(Color.WHITE);
        String hpTxt = model.hp + " / " + GameModel.MAX_HP;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hpTxt, barX + (barW - fm.stringWidth(hpTxt)) / 2, barY + 13);
    }

    void drawSlot(Graphics2D g, int x, int y, String label) {
        g.setColor(new Color(255, 220, 160, 60));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0));
        g.drawRoundRect(x, y, CARD_W, CARD_H, 16, 16);
        g.setFont(new Font("Serif", Font.ITALIC, 16));
        g.setColor(new Color(240, 220, 170));
        g.drawString(label, x + (CARD_W - g.getFontMetrics().stringWidth(label)) / 2, y + CARD_H + 22);
    }

    void drawWeapon(Graphics2D g) {
        int x = weaponX, y = weaponY;
        drawSlot(g, x, y, "Arma equipada");
        if (weaponSprite != null) weaponSprite.draw(g);
        if (model.weapon != null) {
            String s = "Último vencido: " + (model.lastSlain == Integer.MAX_VALUE ? "—" : model.lastSlain);
            g.setColor(new Color(240, 220, 170));
            g.setFont(new Font("Serif", Font.PLAIN, 13));
            g.drawString(s, x + 4, y - 22);
            if (model.weaponBonus > 0) {
                g.setColor(new Color(255, 220, 90));
                g.setFont(new Font("SansSerif", Font.BOLD, 13));
                g.drawString("Mejora +" + model.weaponBonus, x + 4, y - 6);
            }
        }
    }

    void drawParty(Graphics2D g) {
        int x = partyX, y = partyY;
        drawSlot(g, x, y, "Aliados");
        // Doncella + objeto
        if (maidenSprite != null) {
            maidenSprite.tx = x;
            maidenSprite.ty = y;
            maidenSprite.tscale = 0.85;
            maidenSprite.draw(g);
            if (maidenItemSprite != null) {
                maidenItemSprite.tx = x + 60;
                maidenItemSprite.ty = y - 30;
                maidenItemSprite.tscale = 0.55;
                maidenItemSprite.draw(g);
            }
        }
        // Tomo en mano (debajo de la doncella)
        if (tomeSprite != null) {
            tomeSprite.tx = x - 90;
            tomeSprite.ty = y;
            tomeSprite.tscale = 0.7;
            tomeSprite.draw(g);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(new Color(255, 200, 120));
            g.drawString("Tomo (clic = lanzar)", (int)tomeSprite.tx + 4, (int)tomeSprite.ty + (int)(CARD_H * 0.7) + 14);
        }
        // Héroes
        if (model.heroes > 0) {
            int hx = x - 110;
            int hy = y - 80;
            g.setColor(new Color(0,0,0,180));
            g.fillRoundRect(hx, hy, 100, 60, 10, 10);
            g.setColor(new Color(220, 180, 110));
            g.drawRoundRect(hx, hy, 100, 60, 10, 10);
            g.setFont(new Font("Serif", Font.BOLD, 14));
            g.setColor(new Color(255, 230, 180));
            g.drawString("Héroes: " + model.heroes, hx + 8, hy + 22);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.setColor(new Color(220, 220, 220));
            g.drawString("(mitigan 1d6)", hx + 8, hy + 42);
        }
    }

    Rectangle damageButtonBounds(CardSprite s) {
        Card c = s.card;
        if (!c.isMonster() || model.weapon == null || c.value >= model.lastSlain) return null;
        int cx = (int)(s.x + s.w / 2.0);
        int cy = (int)(s.y - s.lift) + s.h + 12 + 40 + 8;
        int iconSize = 38;
        int boxW = iconSize + 18;
        int boxH = iconSize + 22;
        return new Rectangle(cx - boxW / 2, cy, boxW, boxH);
    }

    void drawDamageButton(Graphics2D g, CardSprite s) {
        Rectangle r = damageButtonBounds(s);
        if (r == null) return;
        Point mp = getMousePosition();
        boolean hover = mp != null && r.contains(mp);
        g.setColor(new Color(0, 0, 0, hover ? 220 : 170));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 12, 12);
        g.setColor(new Color(220, 80, 80, hover ? 255 : 200));
        g.setStroke(new BasicStroke(hover ? 3 : 2));
        g.drawRoundRect(r.x, r.y, r.width, r.height, 12, 12);
        int iconSize = 38;
        int ix = r.x + (r.width - iconSize) / 2;
        int iy = r.y + 4;
        if (damageIcon != null) {
            g.drawImage(damageIcon, ix, iy, iconSize, iconSize, null);
        } else {
            g.setColor(new Color(220, 80, 80));
            g.fillOval(ix, iy, iconSize, iconSize);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            FontMetrics fm = g.getFontMetrics();
            String t = "✕";
            g.drawString(t, ix + (iconSize - fm.stringWidth(t)) / 2, iy + iconSize - 10);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.setColor(new Color(255, 210, 210));
        FontMetrics fm = g.getFontMetrics();
        String txt = "A mano";
        g.drawString(txt, r.x + (r.width - fm.stringWidth(txt)) / 2, iy + iconSize + 14);
    }

    void drawCardInfo(Graphics2D g, CardSprite s) {
        Card c = s.card;
        String main; String sub = null; Color col;
        switch (c.type()) {
            case MONSTER: case VAMPIRE: case HAG: case LICH: case DRAGON: {
                int bare = c.value;
                if (model.weapon != null && c.value < model.lastSlain) {
                    int wv = model.weapon.value + model.weaponBonus;
                    int dmg = Math.max(0, c.value - wv);
                    main = "−" + dmg + " HP (arma)";
                    sub  = "−" + bare + " HP a mano";
                } else {
                    main = "−" + bare + " HP";
                    sub  = (model.weapon != null) ? "arma no válida" : null;
                }
                col = (c.type() == CardType.MONSTER) ? new Color(255, 110, 110) : new Color(255, 90, 160);
                if (c.type() == CardType.VAMPIRE) sub = (sub == null ? "" : sub + " · ") + "regenera si se mata a mano";
                if (c.type() == CardType.HAG) sub = (sub == null ? "" : sub + " · ") + "envenena pociones en sala";
                if (c.type() == CardType.LICH) sub = (sub == null ? "" : sub + " · ") + "mata aliados en la sala";
                if (c.type() == CardType.DRAGON) sub = (sub == null ? "" : sub + " · ") + "fuego al colocarse";
                break;
            }
            case WEAPON:
                main = "Arma " + c.value;
                sub  = "(reemplaza la actual)";
                col = new Color(120, 200, 255);
                break;
            case POTION: {
                int heal = Math.min(GameModel.MAX_HP - model.hp, c.value);
                if (model.hagInRoom()) {
                    main = "−" + c.value + " HP";
                    sub  = "envenenada por bruja";
                    col = new Color(180, 80, 220);
                } else if (model.potionUsedThisRoom) {
                    main = "+0 HP";
                    sub  = "ya bebiste poción";
                    col = new Color(180, 180, 180);
                } else {
                    main = "+" + heal + " HP";
                    sub  = "(valor " + c.value + ")";
                    col = new Color(140, 230, 140);
                }
                break;
            }
            case BLACKSMITH:
                main = "Herrero";
                sub  = "Reparar / Mejorar arma";
                col = new Color(255, 200, 90);
                break;
            case MAIDEN:
                main = "Doncella";
                sub  = model.lichInRoom() ? "muere por Lich" : "guarda 1 arma o poción";
                col = new Color(255, 150, 200);
                break;
            case HERO:
                main = "Héroe";
                sub  = model.lichInRoom() ? "muere por Lich" : "mitiga 1d6 daño";
                col = new Color(255, 230, 130);
                break;
            case TOME:
                main = "Tomo de Fuego";
                sub  = model.lichInRoom() ? "muere por Lich" : "destruye toda la sala";
                col = new Color(255, 130, 80);
                break;
            case MERCHANT:
                main = "Mercader";
                sub  = "vende arma/tomo por poción";
                col = new Color(200, 160, 255);
                break;
            default:
                main = c.label(); sub = null; col = Color.WHITE;
        }
        int cx = (int)(s.x + s.w / 2.0);
        int cy = (int)(s.y - s.lift) + s.h + 12;
        Font fMain = new Font("SansSerif", Font.BOLD, 15);
        Font fSub  = new Font("SansSerif", Font.PLAIN, 11);
        FontMetrics fmM = g.getFontMetrics(fMain);
        FontMetrics fmS = g.getFontMetrics(fSub);
        int wMain = fmM.stringWidth(main);
        int wSub  = sub != null ? fmS.stringWidth(sub) : 0;
        int boxW = Math.max(wMain, wSub) + 18;
        int boxH = sub != null ? 38 : 22;
        int boxX = cx - boxW / 2;
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(boxX, cy - 2, boxW, boxH, 10, 10);
        g.setFont(fMain);
        g.setColor(col);
        g.drawString(main, cx - wMain / 2, cy + 14);
        if (sub != null) {
            g.setFont(fSub);
            g.setColor(new Color(225, 225, 225));
            g.drawString(sub, cx - wSub / 2, cy + 30);
        }
    }

    /* ----- Interacción ----- */

    @Override public void mouseMoved(MouseEvent e) {
        CardSprite h = pickRoomCard(e.getX(), e.getY());
        if (h != hovered) {
            if (hovered != null) { hovered.tlift = 0; hovered.tscale = 1.0; }
            hovered = h;
            if (hovered != null) { hovered.tlift = 22; hovered.tscale = 1.06; }
        }
    }

    CardSprite pickRoomCard(int x, int y) {
        for (Card c : model.room) {
            CardSprite s = findSprite(c);
            if (s != null && s.contains(x, y)) return s;
        }
        return null;
    }

    @Override public void mouseClicked(MouseEvent e) {
        if (model.gameOver) return;
        // Clic sobre objeto de doncella → recoger
        if (maidenItemSprite != null) {
            int mx = (int) maidenItemSprite.x, my = (int) maidenItemSprite.y;
            int mw = (int)(maidenItemSprite.w * maidenItemSprite.scale);
            int mh = (int)(maidenItemSprite.h * maidenItemSprite.scale);
            if (e.getX() >= mx && e.getX() <= mx + mw && e.getY() >= my && e.getY() <= my + mh) {
                takeMaidenItem();
                return;
            }
        }
        // Clic sobre tomo en mano → lanzar
        if (tomeSprite != null) {
            int mx = (int) tomeSprite.x, my = (int) tomeSprite.y;
            int mw = (int)(tomeSprite.w * tomeSprite.scale);
            int mh = (int)(tomeSprite.h * tomeSprite.scale);
            if (e.getX() >= mx && e.getX() <= mx + mw && e.getY() >= my && e.getY() <= my + mh) {
                activateTome();
                return;
            }
        }
        // Botón "A mano" tiene prioridad sobre el clic en la carta
        for (Card c2 : new ArrayList<>(model.room)) {
            CardSprite ds = findSprite(c2);
            if (ds == null) continue;
            Rectangle r = damageButtonBounds(ds);
            if (r != null && r.contains(e.getX(), e.getY())) {
                handleMonsterClick(ds, new MouseEvent(this, e.getID(), e.getWhen(),
                        MouseEvent.BUTTON3_DOWN_MASK, e.getX(), e.getY(), 1, false, MouseEvent.BUTTON3));
                return;
            }
        }
        CardSprite s = pickRoomCard(e.getX(), e.getY());
        if (s == null) return;
        Card c = s.card;
        switch (c.type()) {
            case MONSTER: case VAMPIRE: case HAG: case LICH: case DRAGON:
                handleMonsterClick(s, e); break;
            case WEAPON:
                handleWeaponClick(s); break;
            case POTION:
                handlePotionClick(s); break;
            case BLACKSMITH:
                handleBlacksmithClick(s, e); break;
            case MAIDEN:
                handleMaidenClick(s); break;
            case HERO:
                handleHeroClick(s); break;
            case TOME:
                handleTomeClick(s, e); break;
            case MERCHANT:
                handleMerchantClick(s, e); break;
        }
    }

    void handleMonsterClick(CardSprite s, MouseEvent e) {
        Card c = s.card;
        boolean canWeapon = model.weapon != null && c.value < model.lastSlain;
        boolean useWeapon = canWeapon && !SwingUtilities.isRightMouseButton(e);
        // Si hubo clic en botón daño: ya se manejó en otra rama (no implementado aquí)
        boolean wasVampire = c.type() == CardType.VAMPIRE;
        boolean usedW = canWeapon && useWeapon;
        model.fight(c, useWeapon);
        // animación: si vampiro vencido a mano -> al mazo (ya enviado por modelo)
        model.room.remove(c);
        if (wasVampire && !usedW) {
            // Sprite vuelve al mazo boca abajo
            s.tx = deckX; s.ty = deckY; s.trotY = Math.PI;
        } else if (usedW) {
            // a la pila del arma
            s.tx = weaponX + 26 + weaponStack.size() * 8;
            s.ty = weaponY + 18;
            s.tscale = 0.55;
            weaponStack.add(s);
        } else {
            animateDiscard(s);
        }
        if (hovered == s) hovered = null;
        model.postResolve();
        repositionRoom();
    }

    void handleWeaponClick(CardSprite s) {
        Card c = s.card;
        // Si hay doncella libre, ofrecer guardar
        if (model.maiden != null && model.maidenItem == null) {
            JPopupMenu m = new JPopupMenu();
            JMenuItem eq = new JMenuItem("Equipar arma " + c.value);
            eq.addActionListener(ev -> { equipWeapon(s); model.postResolve(); repositionRoom(); });
            JMenuItem mi = new JMenuItem("Dar a la doncella");
            mi.addActionListener(ev -> { giveToMaiden(s); model.postResolve(); repositionRoom(); });
            m.add(eq); m.add(mi);
            m.show(this, (int)(s.x + s.w / 2 - 60), (int)(s.y + s.h - 20));
        } else {
            equipWeapon(s);
            model.postResolve();
            repositionRoom();
        }
    }

    void equipWeapon(CardSprite s) {
        Card c = s.card;
        // descartar arma anterior + pila + herrero
        if (weaponSprite != null) animateDiscard(weaponSprite);
        for (CardSprite ws : weaponStack) animateDiscard(ws);
        weaponStack.clear();
        model.weapon = c;
        model.weaponStackCards.clear();
        model.weaponBonus = 0;
        model.weaponUpgraded = false;
        model.lastSlain = Integer.MAX_VALUE;
        model.message = "Equipas " + c.label() + ".";
        weaponSprite = s;
        s.tx = weaponX; s.ty = weaponY; s.tscale = 1.0; s.tlift = 0; s.trotY = 0;
        model.room.remove(c);
        if (hovered == s) hovered = null;
    }

    void handlePotionClick(CardSprite s) {
        Card c = s.card;
        // Doncella libre: ofrecer guardar
        if (model.maiden != null && model.maidenItem == null) {
            JPopupMenu m = new JPopupMenu();
            JMenuItem dr = new JMenuItem("Beber poción " + c.value);
            dr.addActionListener(ev -> { drinkPotion(s); model.postResolve(); repositionRoom(); });
            JMenuItem mi = new JMenuItem("Dar a la doncella");
            mi.addActionListener(ev -> { giveToMaiden(s); model.postResolve(); repositionRoom(); });
            m.add(dr); m.add(mi);
            m.show(this, (int)(s.x + s.w / 2 - 60), (int)(s.y + s.h - 20));
        } else {
            drinkPotion(s);
            model.postResolve();
            repositionRoom();
        }
    }

    void drinkPotion(Card c) {
        // versión por carta directa (usado al recoger objeto de doncella)
        if (model.hagInRoom()) {
            int real = model.applyDamage(c.value, "Poción envenenada (" + c.label() + ")");
            model.message = "La poción " + c.label() + " está envenenada (-" + real + " HP).";
        } else if (model.potionUsedThisRoom) {
            model.message = "La poción " + c.label() + " se desperdicia (sólo una por sala).";
        } else {
            int heal = Math.min(GameModel.MAX_HP - model.hp, c.value);
            model.hp += heal;
            model.potionUsedThisRoom = true;
            model.message = "Bebes " + c.label() + " (+" + heal + " HP).";
        }
    }

    void drinkPotion(CardSprite s) {
        drinkPotion(s.card);
        animateDiscard(s);
        model.room.remove(s.card);
        if (hovered == s) hovered = null;
    }

    void giveToMaiden(CardSprite s) {
        model.maidenItem = s.card;
        maidenItemSprite = s;
        model.room.remove(s.card);
        model.message = "La doncella guarda " + s.card.label() + ".";
        if (hovered == s) hovered = null;
    }

    void takeMaidenItem() {
        if (model.maiden == null || model.maidenItem == null) return;
        Card item = model.maidenItem;
        CardSprite itemS = maidenItemSprite;
        // descartar doncella
        if (maidenSprite != null) animateDiscard(maidenSprite);
        maidenSprite = null;
        model.maiden = null;
        model.maidenItem = null;
        maidenItemSprite = null;
        if (item.type() == CardType.WEAPON) {
            // equipar
            if (weaponSprite != null) animateDiscard(weaponSprite);
            for (CardSprite ws : weaponStack) animateDiscard(ws);
            weaponStack.clear();
            model.weapon = item;
            model.weaponStackCards.clear();
            model.weaponBonus = 0;
            model.weaponUpgraded = false;
            model.lastSlain = Integer.MAX_VALUE;
            weaponSprite = itemS;
            itemS.tx = weaponX; itemS.ty = weaponY; itemS.tscale = 1.0; itemS.tlift = 0; itemS.trotY = 0;
            model.message = "Recoges el arma de la doncella y la equipas.";
        } else if (item.type() == CardType.TOME) {
            model.tome = item;
            tomeSprite = itemS;
            model.message = "Recoges el tomo de la doncella.";
        } else {
            // poción: bebe
            drinkPotion(item);
            animateDiscard(itemS);
        }
    }

    void handleBlacksmithClick(CardSprite s, MouseEvent e) {
        Card c = s.card;
        if (model.lichInRoom()) {
            // los herreros no son aliados según las reglas dadas, pero por consistencia: no
            // (sólo aliados son hero/maiden/tome)
        }
        JPopupMenu m = new JPopupMenu();
        JMenuItem rep = new JMenuItem("Reparar (quitar último monstruo de la pila)");
        rep.setEnabled(!model.weaponStackCards.isEmpty());
        rep.addActionListener(ev -> {
            Card top = model.weaponStackCards.remove(model.weaponStackCards.size() - 1);
            // Buscar el sprite correspondiente en weaponStack
            CardSprite topS = null;
            for (int i = weaponStack.size() - 1; i >= 0; i--) {
                if (weaponStack.get(i).card == top) { topS = weaponStack.remove(i); break; }
            }
            if (topS != null) animateDiscard(topS);
            model.message = "Herrero repara y descarta " + top.label() + ".";
            animateDiscard(s);
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
        });
        JMenuItem up = new JMenuItem("Mejorar (+1 daño, sólo arma sin daño)");
        up.setEnabled(model.weaponUndamaged() && !model.weaponUpgraded);
        up.addActionListener(ev -> {
            model.weaponBonus += 1;
            model.weaponUpgraded = true;
            model.message = "Herrero mejora el arma. Bonus +" + model.weaponBonus + ".";
            animateDiscard(s);
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
        });
        JMenuItem disc = new JMenuItem("Descartar herrero");
        disc.addActionListener(ev -> {
            animateDiscard(s);
            model.room.remove(c);
            model.message = "Descartas al herrero.";
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
        });
        m.add(rep); m.add(up); m.add(disc);
        m.show(this, (int)(s.x + s.w / 2 - 80), (int)(s.y + s.h - 20));
    }

    void handleMaidenClick(CardSprite s) {
        Card c = s.card;
        if (model.lichInRoom()) {
            model.message = "El Lich mata a la doncella " + c.label() + ".";
            animateDiscard(s);
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
            return;
        }
        if (model.maiden != null) {
            // ya hay doncella: descartar la actual + objeto y reclutar la nueva
            if (maidenItemSprite != null) animateDiscard(maidenItemSprite);
            if (maidenSprite != null) animateDiscard(maidenSprite);
            model.maidenItem = null; model.maiden = null; maidenItemSprite = null; maidenSprite = null;
        }
        model.maiden = c;
        maidenSprite = s;
        s.tx = partyX; s.ty = partyY; s.tscale = 0.85;
        model.message = "Reclutas a la doncella.";
        model.room.remove(c);
        if (hovered == s) hovered = null;
        model.postResolve();
        repositionRoom();
    }

    void handleHeroClick(CardSprite s) {
        Card c = s.card;
        if (model.lichInRoom()) {
            model.message = "El Lich mata al héroe.";
            animateDiscard(s);
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
            return;
        }
        model.heroes++;
        model.message = "Reclutas un héroe (heroes=" + model.heroes + ").";
        animateDiscard(s);
        model.room.remove(c);
        if (hovered == s) hovered = null;
        model.postResolve();
        repositionRoom();
    }

    void handleTomeClick(CardSprite s, MouseEvent e) {
        Card c = s.card;
        if (model.lichInRoom()) {
            model.message = "El Lich destruye el tomo.";
            animateDiscard(s);
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
            return;
        }
        JPopupMenu m = new JPopupMenu();
        JMenuItem use = new JMenuItem("Lanzar ahora (destruye monstruos de la sala)");
        use.addActionListener(ev -> {
            castTome();
            animateDiscard(s);
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
        });
        JMenuItem hold = new JMenuItem("Guardar tomo");
        hold.addActionListener(ev -> {
            if (model.tome != null) {
                model.message = "Ya tienes un tomo; descartas el anterior.";
                if (tomeSprite != null) animateDiscard(tomeSprite);
            }
            model.tome = c; tomeSprite = s;
            s.tx = partyX - 90; s.ty = partyY; s.tscale = 0.7;
            model.room.remove(c);
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
        });
        JMenuItem mi = new JMenuItem("Dar a la doncella");
        mi.setEnabled(model.maiden != null && model.maidenItem == null);
        mi.addActionListener(ev -> { giveToMaiden(s); model.postResolve(); repositionRoom(); });
        m.add(use); m.add(hold); m.add(mi);
        m.show(this, (int)(s.x + s.w / 2 - 100), (int)(s.y + s.h - 20));
    }

    void castTome() {
        // destruir todos los monstruos de la sala
        java.util.List<Card> kill = new ArrayList<>();
        for (Card c : model.room) if (c.isMonster()) kill.add(c);
        for (Card c : kill) {
            CardSprite cs = findSprite(c);
            if (cs != null) animateDiscard(cs);
            model.room.remove(c);
        }
        model.message = "El tomo de fuego destruye " + kill.size() + " monstruos.";
        // tomo se descarta tras uso (ya se descarta en handleTomeClick o activateTome)
    }

    void activateTome() {
        if (model.tome == null) return;
        castTome();
        if (tomeSprite != null) animateDiscard(tomeSprite);
        model.tome = null;
        tomeSprite = null;
        model.postResolve();
        repositionRoom();
    }

    void handleMerchantClick(CardSprite s, MouseEvent e) {
        Card c = s.card;
        JPopupMenu m = new JPopupMenu();
        // Vender arma equipada
        JMenuItem sw = new JMenuItem(model.weapon != null
                ? "Vender arma equipada por poción " + model.weaponStackLowest()
                : "Vender arma equipada (no tienes)");
        sw.setEnabled(model.weapon != null);
        sw.addActionListener(ev -> sellEquippedWeapon(s, c));
        // Vender arma de doncella
        boolean maidenWeapon = model.maiden != null && model.maidenItem != null
                && model.maidenItem.type() == CardType.WEAPON;
        JMenuItem sm = new JMenuItem(maidenWeapon
                ? "Vender arma de la doncella por poción " + model.maidenItem.value
                : "Vender arma de la doncella (no aplicable)");
        sm.setEnabled(maidenWeapon);
        sm.addActionListener(ev -> sellMaidenWeapon(s, c));
        // Vender tomo
        JMenuItem st = new JMenuItem(model.tome != null
                ? "Vender Tomo por poción 10"
                : "Vender Tomo (no tienes)");
        st.setEnabled(model.tome != null);
        st.addActionListener(ev -> sellTome(s, c));
        // Descartar
        JMenuItem disc = new JMenuItem("Despedir mercader (descartar)");
        disc.addActionListener(ev -> {
            animateDiscard(s);
            model.room.remove(c);
            model.message = "Despides al mercader.";
            if (hovered == s) hovered = null;
            model.postResolve();
            repositionRoom();
        });
        m.add(sw); m.add(sm); m.add(st); m.addSeparator(); m.add(disc);
        m.show(this, (int)(s.x + s.w / 2 - 100), (int)(s.y + s.h - 20));
    }

    void sellEquippedWeapon(CardSprite merchant, Card merchantCard) {
        int potion = model.weaponStackLowest();
        // descartar arma + pila + bonus
        if (weaponSprite != null) animateDiscard(weaponSprite);
        for (CardSprite ws : weaponStack) animateDiscard(ws);
        weaponStack.clear();
        weaponSprite = null;
        model.weapon = null;
        model.weaponStackCards.clear();
        model.weaponBonus = 0; model.weaponUpgraded = false;
        model.lastSlain = Integer.MAX_VALUE;
        // beber poción inmediata
        int heal = Math.min(GameModel.MAX_HP - model.hp, potion);
        model.hp += heal;
        model.message = "Vendes el arma. +" + heal + " HP.";
        // descartar mercader
        animateDiscard(merchant);
        model.room.remove(merchantCard);
        if (hovered == merchant) hovered = null;
        model.postResolve();
        repositionRoom();
    }

    void sellMaidenWeapon(CardSprite merchant, Card merchantCard) {
        int potion = model.maidenItem.value;
        if (maidenItemSprite != null) animateDiscard(maidenItemSprite);
        if (maidenSprite != null) animateDiscard(maidenSprite);
        model.maiden = null; model.maidenItem = null;
        maidenSprite = null; maidenItemSprite = null;
        int heal = Math.min(GameModel.MAX_HP - model.hp, potion);
        model.hp += heal;
        model.message = "Vendes el arma de la doncella. +" + heal + " HP.";
        animateDiscard(merchant);
        model.room.remove(merchantCard);
        if (hovered == merchant) hovered = null;
        model.postResolve();
        repositionRoom();
    }

    void sellTome(CardSprite merchant, Card merchantCard) {
        if (tomeSprite != null) animateDiscard(tomeSprite);
        model.tome = null; tomeSprite = null;
        int heal = Math.min(GameModel.MAX_HP - model.hp, 10);
        model.hp += heal;
        model.message = "Vendes el tomo por una poción de 10. +" + heal + " HP.";
        animateDiscard(merchant);
        model.room.remove(merchantCard);
        if (hovered == merchant) hovered = null;
        model.postResolve();
        repositionRoom();
    }

    void animateDiscard(CardSprite s) {
        int idx = discardPile.size();
        s.tx = discardX + (idx % 5) * 4;
        s.ty = discardY + (idx % 5) * 4;
        s.tscale = 0.95;
        s.trotY = Math.PI * 2;
        s.topacity = 0.0;
        s.tlift = 0;
        discardPile.add(s);
    }

    void doSkip() {
        if (!model.canSkip()) return;
        for (Card c : model.room) {
            CardSprite s = findSprite(c);
            if (s == null) continue;
            s.tx = deckX; s.ty = deckY; s.trotY = Math.PI; s.tlift = 0; s.tscale = 1.0;
        }
        model.skip();
        Timer t = new Timer(380, e -> { model.dealRoom(); repositionRoom(); });
        t.setRepeats(false);
        t.start();
    }

    @Override public void mousePressed(MouseEvent e) {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {
        if (hovered != null) { hovered.tlift = 0; hovered.tscale = 1.0; hovered = null; }
    }
    @Override public void mouseDragged(MouseEvent e) {}
}
