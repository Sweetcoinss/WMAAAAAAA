package fr.tibkpl.openswat4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Couche tactile de SWAT 4.
 *
 * Le jeu est un FPS tactique de 2005 : clavier + souris.  Les raccourcis
 * reellement utilises par le jeu sont ceux de Content/System/User.ini
 * (section [Engine.Input]) :
 *
 *   W / S            avancer / reculer
 *   A / D            deplacement laterale
 *   Maj (Shift)      courir
 *   C                s'accroupir
 *   Q / E            s'abaisser a gauche / a droite
 *   souris           regard (MouseX / MouseY)
 *   clic gauche      tirer
 *   clic droit       interface de commandement (CommandInterface du jeu)
 *   F                obeir / interagir
 *   G                saisir
 *   R                recharger
 *   Z                zoom
 *   V                lampe torche
 *   /                arme suivante
 *   T / Y            messagerie
 *
 * L'interface de commandement reste celle du jeu : un clic droit ouvre la
 * roue de commandes d'origine, que l'on pilote ensuite au toucher comme un
 * menu souris.
 */
public class Swat4OverlayView extends View {

    // --- raccourcis du jeu (valeurs de touches ASCII / X11) --------------
    private static final int K_W = 'w', K_S = 's', K_A = 'a', K_D = 'd';
    private static final int K_SHIFT = 0xffe1;          // XK_Shift_L
    private static final int K_C = 'c', K_Q = 'q', K_E = 'e';
    private static final int K_F = 'f', K_G = 'g', K_R = 'r';
    private static final int K_Z = 'z', K_V = 'v', K_SLASH = '/';

    private static final int MOUSE_LEFT = 1, MOUSE_RIGHT = 3;

    /** Une action tactile : touche du jeu ou bouton de souris. */
    private static final class Action {
        final String label;
        final int key;          // keysym, ou 0
        final int button;       // bouton souris, ou 0
        final boolean toggle;   // appui/relachement automatique
        final boolean hold;     // maintenu pendant l'appui
        final float fx, fy;     // position relative (0..1)
        final float radius;     // rayon relatif (0..1 de la plus petite dimension)
        boolean pressed;

        Action(String label, int key, int button, boolean toggle, boolean hold,
               float fx, float fy, float radius) {
            this.label = label;
            this.key = key;
            this.button = button;
            this.toggle = toggle;
            this.hold = hold;
            this.fx = fx;
            this.fy = fy;
            this.radius = radius;
        }
    }

    private final InputBridge input;
    private final List<Action> actions = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- manche virtuelle ------------------------------------------------
    private int stickPointer = -1;
    private float stickCx, stickCy, stickR;
    private float stickKnobX, stickKnobY;
    private boolean wDown, sDown, aDown, dDown, runDown;

    // --- regard -----------------------------------------------------------
    private int lookPointer = -1;
    private float lookLastX, lookLastY;
    private float lookSensitivity = 0.55f;

    /** Hauteur utile de l'ecran virtuel (le ratio est applique a l'ecran). */
    private float virtualWidth = 1280f, virtualHeight = 720f;
    private boolean visible = true;

    public Swat4OverlayView(Context context, InputBridge input) {
        super(context);
        this.input = input;
        text.setColor(Color.WHITE);
        text.setTextSize(28f);
        text.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);

        // Boutons : positions en fraction de l'ecran (bas = actions de jeu).
        actions.add(new Action("TIR", 0, MOUSE_LEFT, false, true, 0.87f, 0.80f, 0.095f));
        actions.add(new Action("CMD", 0, MOUSE_RIGHT, false, false, 0.72f, 0.85f, 0.058f));
        actions.add(new Action("ACT", K_F, 0, false, false, 0.70f, 0.66f, 0.050f));
        actions.add(new Action("RECH", K_R, 0, false, false, 0.87f, 0.60f, 0.048f));
        actions.add(new Action("ACC", K_C, 0, true, false, 0.60f, 0.74f, 0.048f));
        actions.add(new Action("COURIR", K_SHIFT, 0, false, true, 0.46f, 0.86f, 0.055f));
        actions.add(new Action("PREND", K_G, 0, false, false, 0.55f, 0.60f, 0.048f));
        actions.add(new Action("GAUCHE", K_Q, 0, false, false, 0.38f, 0.76f, 0.045f));
        actions.add(new Action("DROITE", K_E, 0, false, false, 0.38f, 0.62f, 0.045f));
        actions.add(new Action("ARME+", K_SLASH, 0, false, false, 0.95f, 0.71f, 0.042f));
        actions.add(new Action("ZOOM", K_Z, 0, true, false, 0.80f, 0.52f, 0.042f));
        actions.add(new Action("LAMPE", K_V, 0, true, false, 0.68f, 0.52f, 0.042f));
    }

    public void setVirtualSize(float w, float h) {
        virtualWidth = w;
        virtualHeight = h;
    }

    public void setVisibleHud(boolean v) {
        visible = v;
        invalidate();
    }

    public boolean isHudVisible() {
        return visible;
    }

    /** Facteur de conversion : pixels ecran -> pixels de l'ecran virtuel. */
    private float scale() {
        return Math.min(getWidth() / virtualWidth, getHeight() / virtualHeight);
    }

    // ------------------------------------------------------------------
    // dessin
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;

        float s = Math.min(getWidth(), getHeight());
        // manche virtuelle
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.argb(70, 255, 255, 255));
        canvas.drawCircle(stickCx, stickCy, stickR, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(90, 255, 255, 255));
        canvas.drawCircle(stickKnobX, stickKnobY, stickR * 0.38f, paint);

        // boutons
        for (Action a : actions) {
            float r = a.radius * s;
            float x = a.fx * getWidth();
            float y = a.fy * getHeight();
            paint.setColor(a.pressed ? Color.argb(150, 220, 60, 60) : Color.argb(70, 0, 0, 0));
            canvas.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(Color.argb(160, 255, 255, 255));
            canvas.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.FILL);
            text.setTextSize(Math.max(18f, r * 0.42f));
            text.setColor(Color.WHITE);
            canvas.drawText(a.label, x, y + text.getTextSize() * 0.35f, text);
        }
    }

    // ------------------------------------------------------------------
    // toucher
    // ------------------------------------------------------------------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                Action hit = hitAction(x, y);
                if (hit != null) {
                    hit.pressed = true;
                    pointerAction.put(id, hit);
                    press(hit);
                    invalidate();
                    return true;
                }
                if (isStickZone(x, y)) {
                    stickPointer = id;
                    stickCx = x; stickCy = y;
                    stickR = Math.min(getWidth(), getHeight()) * 0.11f;
                    updateStick(x, y);
                    return true;
                }
                // reste de l'ecran : regard (deplacement relatif de la souris)
                lookPointer = id;
                lookLastX = x;
                lookLastY = y;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    float x = event.getX(i), y = event.getY(i);
                    if (id == stickPointer) {
                        updateStick(x, y);
                    } else if (id == lookPointer) {
                        float dx = (x - lookLastX) * lookSensitivity;
                        float dy = (y - lookLastY) * lookSensitivity;
                        lookLastX = x;
                        lookLastY = y;
                        // la souris du jeu attend un deplacement relatif
                        input.tapMotion((int) dx, (int) dy);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int id = event.getPointerId(index);
                releasePointer(id, event, index, action);
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private final Map<Integer, Action> pointerAction = new HashMap<>();

    private void releasePointer(int id, MotionEvent event, int index, int action) {
        Action a = pointerAction.remove(id);
        if (a != null) {
            a.pressed = false;
            if (!a.toggle) release(a);
            invalidate();
            return;
        }
        if (id == stickPointer) {
            stickPointer = -1;
            releaseMoveKeys();
            stickKnobX = stickCx;
            stickKnobY = stickCy;
            invalidate();
            return;
        }
        if (id == lookPointer) {
            lookPointer = -1;
        }
    }

    private boolean isStickZone(float x, float y) {
        float s = Math.min(getWidth(), getHeight());
        // quart inferior gauche
        return x < getWidth() * 0.42f && y > getHeight() * 0.45f;
    }

    private Action hitAction(float x, float y) {
        float s = Math.min(getWidth(), getHeight());
        for (Action a : actions) {
            float r = a.radius * s * 1.25f;   // zone tactile un peu plus large
            float dx = x - a.fx * getWidth();
            float dy = y - a.fy * getHeight();
            if (dx * dx + dy * dy <= r * r) return a;
        }
        return null;
    }

    private void press(Action a) {
        if (a.button != 0) {
            input.tapButton(a.button, true);
        } else if (a.key != 0) {
            input.tapKey(a.key, true);
            if (!a.hold && !a.toggle) {
                // appui bref : on relache aussitot (le jeu lit l'evenement)
                input.tapKey(a.key, false);
            }
        }
    }

    private void release(Action a) {
        if (a.button != 0) {
            input.tapButton(a.button, false);
        } else if (a.key != 0) {
            input.tapKey(a.key, false);
        }
    }

    /** Manche virtuelle -> W/A/S/D (+ course quand la manche est poussee a fond). */
    private void updateStick(float x, float y) {
        float dx = x - stickCx;
        float dy = y - stickCy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float max = stickR;
        if (len > max) {
            dx = dx / len * max;
            dy = dy / len * max;
        }
        stickKnobX = stickCx + dx;
        stickKnobY = stickCy + dy;

        float dead = max * 0.22f;
        boolean nw = dy < -dead;   // pousser vers le haut = avancer
        boolean ns = dy > dead;
        boolean na = dx < -dead;
        boolean nd = dx > dead;
        if (nw != wDown) { input.tapKey(K_W, nw); wDown = nw; }
        if (ns != sDown) { input.tapKey(K_S, ns); sDown = ns; }
        if (na != aDown) { input.tapKey(K_A, na); aDown = na; }
        if (nd != dDown) { input.tapKey(K_D, nd); dDown = nd; }

        boolean run = len > max * 0.92f;
        if (run != runDown) { input.tapKey(K_SHIFT, run); runDown = run; }
        invalidate();
    }

    private void releaseMoveKeys() {
        if (wDown) { input.tapKey(K_W, false); wDown = false; }
        if (sDown) { input.tapKey(K_S, false); sDown = false; }
        if (aDown) { input.tapKey(K_A, false); aDown = false; }
        if (dDown) { input.tapKey(K_D, false); dDown = false; }
        if (runDown) { input.tapKey(K_SHIFT, false); runDown = false; }
    }
}
