package fr.tibkpl.openswat4;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Affiche l'image de l'ecran X virtuel (Xvfb -fbdir) : le fichier XWD est mappe en memoire
 * (partage avec Xvfb) et envoye au GPU a chaque rafraichissement, mis a l'echelle de l'ecran
 * en conservant le format d'image.
 *
 * <p><b>Generation d'images</b> : le moteur UE2 produit une image toutes les 30 ms environ,
 * alors que l'ecran en affiche 60 par seconde.  Quand cette option est active, les deux
 * dernieres images sont conservees et l'affichage est interpole entre elles, ce qui
 * double la fluidite apparente sans rien demander de plus au jeu.  Le facteur
 * d'interpolation suit le temps ecoule depuis la derniere image produite par le
 * moteur ; si le jeu est trop lent, la derniere image est affichee telle quelle.
 */
final class FramebufferView extends GLSurfaceView {

    private final FbRenderer renderer;

    FramebufferView(Context context, File framebufferFile, int targetFps, boolean frameGeneration) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new FbRenderer(framebufferFile, targetFps, frameGeneration);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(false);
    }

    private static final class FbRenderer implements Renderer {
        private static final String VERTEX =
                "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;\n"
                + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }";
        // Xvfb 24 bits stocke les pixels en BGRX : on remet les canaux dans l'ordre.
        // uMix = 0 : image precedente ; uMix = 1 : image la plus recente.
        private static final String FRAGMENT =
                "precision mediump float; varying vec2 vTex;\n"
                + "uniform sampler2D uPrev; uniform sampler2D uCur; uniform float uMix;\n"
                + "void main() {\n"
                + "  vec3 p = texture2D(uPrev, vTex).bgr;\n"
                + "  vec3 c = texture2D(uCur, vTex).bgr;\n"
                + "  gl_FragColor = vec4(mix(p, c, uMix), 1.0);\n"
                + "}";

        private final File file;
        private MappedByteBuffer mapped;
        private ByteBuffer pixels;
        private int fbWidth;
        private int fbHeight;
        private int fbStride;
        private int viewWidth;
        private int viewHeight;
        private int program;
        private boolean textureAllocated;
        private FloatBuffer quad;
        private long lastAttempt;

        /** Generation d'images : deux textures (precedente / courante). */
        private final boolean frameGeneration;
        private int texPrev;
        private int texCur;
        private boolean havePrev;

        /** Intervalle minimal entre deux envois d'image (0 : pas de limite). */
        private final long frameIntervalNanos;
        private long lastFrameNanos;

        /** Detection du changement d'image et estimation du rythme du moteur. */
        private long lastSignature;
        private long lastChangeNanos;
        private long estimatedIntervalNanos;
        private boolean firstFrame = true;

        FbRenderer(File file, int targetFps, boolean frameGeneration) {
            this.file = file;
            this.frameGeneration = frameGeneration;
            // Avec generation d'images, l'ecran affiche a sa frequence native (60 Hz en
            // general) et l'interpolation comble les images manquantes ; sans, on
            // limite l'affichage au rythme du moteur pour epargner GPU et batterie.
            int fps = targetFps > 0 ? targetFps : 30;
            this.frameIntervalNanos = frameGeneration ? 0L : 1_000_000_000L / fps;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = buildProgram();
            int[] tex = new int[2];
            GLES20.glGenTextures(2, tex, 0);
            texCur = tex[0];
            texPrev = tex[1];
            for (int t : tex) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            textureAllocated = false;
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            viewWidth = width;
            viewHeight = height;
            GLES20.glViewport(0, 0, width, height);
            quad = null;
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            long now = System.nanoTime();
            if (frameIntervalNanos > 0 && lastFrameNanos != 0
                    && now - lastFrameNanos < frameIntervalNanos) {
                return;
            }
            lastFrameNanos = now;

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (pixels == null && !openFramebuffer()) return;

            // Le moteur a-t-il produit une nouvelle image ?
            long signature = frameSignature();
            if (signature != lastSignature) {
                long delta = lastChangeNanos == 0 ? 0 : now - lastChangeNanos;
                if (delta > 1_000_000L && delta < 2_000_000_000L) {
                    estimatedIntervalNanos = estimatedIntervalNanos == 0
                            ? delta : (estimatedIntervalNanos * 3 + delta) / 4;
                }
                lastChangeNanos = now;
                lastSignature = signature;
                uploadFrame();
            }

            float mix = 1f;
            if (frameGeneration && havePrev && estimatedIntervalNanos > 0) {
                float phase = (float) (now - lastChangeNanos) / (float) estimatedIntervalNanos;
                if (phase > 1f) phase = 1f;
                if (phase < 0f) phase = 0f;
                // Interpolation douce : moins de secousse sur les mouvements lents.
                mix = phase * phase * (3f - 2f * phase);
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texPrev);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texCur);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

            if (quad == null) quad = buildQuad();
            GLES20.glUseProgram(program);
            int aPos = GLES20.glGetAttribLocation(program, "aPos");
            int aTex = GLES20.glGetAttribLocation(program, "aTex");
            int uPrev = GLES20.glGetUniformLocation(program, "uPrev");
            int uCur = GLES20.glGetUniformLocation(program, "uCur");
            int uMix = GLES20.glGetUniformLocation(program, "uMix");
            GLES20.glUniform1i(uPrev, 0);
            GLES20.glUniform1i(uCur, 1);
            GLES20.glUniform1f(uMix, mix);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        /** Envoie l'image courante au GPU (dans la texture "precedente", puis permutation). */
        private void uploadFrame() {
            int target = havePrev ? texPrev : texCur;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target);
            pixels.position(0);
            if (!textureAllocated) {
                // Premiere image : les deux textures sont allouees avec le meme contenu,
                // la generation d'images n'ayant pas encore d'image precedente.
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fbWidth, fbHeight, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                int other = (target == texCur) ? texPrev : texCur;
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, other);
                pixels.position(0);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fbWidth, fbHeight, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                textureAllocated = true;
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, fbWidth, fbHeight,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            }
            if (havePrev) {
                int swap = texCur;
                texCur = texPrev;
                texPrev = swap;
            } else {
                havePrev = true;
            }
        }

        /**
         * Empreinte de l'image : echantillon d'une grille de points.  Si l'empreinte
         * change, le moteur a produit une nouvelle image.
         */
        private long frameSignature() {
            if (firstFrame) {
                firstFrame = false;
                return 0;
            }
            long h = 1469598103934665603L;
            int stepX = Math.max(1, fbWidth / 48);
            int stepY = Math.max(1, fbHeight / 27);
            for (int y = 0; y < fbHeight; y += stepY) {
                int row = y * fbStride;
                for (int x = 0; x < fbWidth; x += stepX) {
                    int v = pixels.getInt(row + x * 4);
                    h = (h ^ (v & 0xFFFFFFFFL)) * 1099511628211L;
                }
            }
            return h;
        }

        /** Mappe le fichier XWD une fois que Xvfb l'a cree. */
        private boolean openFramebuffer() {
            long now = System.currentTimeMillis();
            if (now - lastAttempt < 250) return false;
            lastAttempt = now;
            if (!file.isFile() || file.length() < 100) return false;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                mapped.order(ByteOrder.BIG_ENDIAN);
                int headerSize = mapped.getInt(0);
                int width = mapped.getInt(16);
                int height = mapped.getInt(20);
                int bitsPerPixel = mapped.getInt(44);
                int bytesPerLine = mapped.getInt(48);
                int ncolors = mapped.getInt(76);
                int offset = headerSize + ncolors * 12;
                if (bitsPerPixel != 32 || bytesPerLine != width * 4
                        || offset + (long) bytesPerLine * height > channel.size()) {
                    return false;
                }
                mapped.position(offset);
                pixels = mapped.slice();
                fbWidth = width;
                fbHeight = height;
                fbStride = bytesPerLine;
                quad = null;
                lastSignature = 0;
                firstFrame = true;
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /** Rectangle plein ecran, avec bandes noires si les formats d'image different. */
        private FloatBuffer buildQuad() {
            float sx = 1f;
            float sy = 1f;
            if (fbWidth > 0 && viewWidth > 0) {
                float fbAspect = (float) fbWidth / fbHeight;
                float viewAspect = (float) viewWidth / viewHeight;
                if (viewAspect > fbAspect) sx = fbAspect / viewAspect;
                else sy = viewAspect / fbAspect;
            }
            float[] data = {
                    -sx, -sy, 0f, 1f,
                    sx, -sy, 1f, 1f,
                    -sx, sy, 0f, 0f,
                    sx, sy, 1f, 0f,
            };
            FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(data).position(0);
            return buffer;
        }

        private static int buildProgram() {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            return prog;
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }
    }
}
