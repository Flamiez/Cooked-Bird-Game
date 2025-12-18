package com.cookedbirdgame;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.Preferences;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FirstScreen implements Screen {
    private Preferences preferences;
    private enum GameState { PLAYING, PAUSED, GAME_OVER }
    private GameState state = GameState.PLAYING;
    private static final float WORLD_WIDTH = 1920f;
    private static final float WORLD_HEIGHT = 1080f;
    private static final float PPM = 100f;
    private static final float TIME_STEP = 1f / 60f;
    private static final float JUMP_HEIGHT_M = 0.8f;
    private OrthographicCamera camera;
    private Viewport viewport;
    private SpriteBatch batch;
    private BitmapFont font;
    private Texture birdTexture;
    private Texture pipeTexture;
    private World world;
    private float accumulator = 0f;
    private Body birdBody;
    private final Vector2 birdStartPx = new Vector2(0.2f * WORLD_WIDTH, 0.5f * WORLD_HEIGHT);
    private float jumpVelocityMps;
    private Texture overlayTexture;
    private Texture buttonTexture;
    private Texture backgroundTexture;

    private final float PAUSE_BUTTON_SIZE = 120f;
    private final float PAUSE_BUTTON_MARGIN = 20f;

    private class PipePair {
        Body topBody;
        Body bottomBody;
        Body scoreSensor;
        boolean scored = false;
        float upperPipeYpx;
        float upperPipeHeightPx;
        float lowerPipeHeightPx;

        Vector2 prevTopVel = new Vector2(0,0);
        Vector2 prevBottomVel = new Vector2(0,0);
        Vector2 prevSensorVel = new Vector2(0,0);
    }

    private final List<PipePair> pipePairs = new ArrayList<>();
    private float timePassed = 0f;
    private float pipeSpawnTimer = 0f;
    private float pipeSpawnIntervalBase = 2f;
    private float pipeSpeedPx;
    private float maxPipeSpeedPx = WORLD_WIDTH / 0.4f;
    private int score = 0;
    private int highScore = 0;
    private Vector2 prevBirdVel = new Vector2(0,0);
    private Sound deathSound;
    private Sound scoreSound;
    private Sound flapSound;
    private boolean hasPlayedDeathSound = false;
    private GlyphLayout layout = new GlyphLayout();
    private float lastGapCenterY = -1f;


    @Override
    public void show() {
        preferences = Gdx.app.getPreferences("BirdGamePrefs");

        highScore = preferences.getInteger("highScore", 0);

        deathSound = Gdx.audio.newSound(Gdx.files.internal("death.mp3"));
        scoreSound = Gdx.audio.newSound(Gdx.files.internal("score.mp3"));
        flapSound = Gdx.audio.newSound(Gdx.files.internal("flap.mp3"));



        camera = new OrthographicCamera();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT, camera);
        camera.position.set(WORLD_WIDTH / 2f, WORLD_HEIGHT / 2f, 0f);
        camera.update();

        batch = new SpriteBatch();
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal("fonts/OpenSans-ExtraBold.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        parameter.size = 64;
        parameter.minFilter = Texture.TextureFilter.Linear;
        parameter.magFilter = Texture.TextureFilter.Linear;
        font = generator.generateFont(parameter);
        generator.dispose();

        backgroundTexture = new Texture("bg.png");
        backgroundTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

        birdTexture = new Texture("bird.png");
        pipeTexture = new Texture("pipe.png");

        Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        p.setColor(new Color(Color.BLACK));
        p.fill();
        overlayTexture = new Texture(p);
        p.dispose();

        Pixmap b = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        b.setColor(Color.WHITE);
        b.fill();
        buttonTexture = new Texture(b);
        b.dispose();

        float g = 9.8f;
        jumpVelocityMps = (float) Math.sqrt(2f * g * JUMP_HEIGHT_M);

        world = new World(new Vector2(0f, -9.8f), true);

        world.setContactListener(new ContactListener() {
            @Override
            public void beginContact(Contact contact) {
                Fixture a = contact.getFixtureA();
                Fixture b = contact.getFixtureB();
                Object ad = a.getUserData();
                Object bd = b.getUserData();

                if (("bird".equals(ad) && "pipe".equals(bd)) || ("bird".equals(bd) && "pipe".equals(ad))) {
                    if (state == GameState.PLAYING) {
                        state = GameState.GAME_OVER;
                        stopAllPipes();
                        stopBird();
                    }
                }

                if (("bird".equals(ad) && "score".equals(bd)) || ("bird".equals(bd) && "score".equals(ad))) {
                    Body sensorBody = "score".equals(ad) ? a.getBody() : b.getBody();
                    for (PipePair p : pipePairs) {
                        if (p.scoreSensor == sensorBody && !p.scored) {
                            score++;
                            p.scored = true;
                            scoreSound.play(0.5f);
                            if (score > highScore) {
                                highScore = score;
                                preferences.putInteger("highScore", highScore);
                                preferences.flush();
                            }
                            break;
                        }
                    }
                }
            }
            @Override public void endContact(Contact contact) {}
            @Override public void preSolve(Contact contact, Manifold oldManifold) {}
            @Override public void postSolve(Contact contact, ContactImpulse impulse) {}
        });

        createBirdBody();
        pipeSpeedPx = WORLD_WIDTH * (1f / 8f);
    }

    private void createBirdBody() {
        BodyDef bd = new BodyDef();
        bd.type = BodyDef.BodyType.DynamicBody;
        bd.position.set(birdStartPx.x / PPM, birdStartPx.y / PPM);
        bd.fixedRotation = true;
        birdBody = world.createBody(bd);

        float birdScale = 4f;
        float radiusPx = (birdTexture.getWidth() / 2f) * birdScale;
        CircleShape cs = new CircleShape();
        cs.setRadius(radiusPx / PPM);

        FixtureDef fd = new FixtureDef();
        fd.shape = cs;
        fd.density = 1f;
        fd.friction = 0f;
        fd.restitution = 0f;
        Fixture f = birdBody.createFixture(fd);
        f.setUserData("bird");
        cs.dispose();
        birdBody.setAwake(true);
    }

    private Body createKinematicBox(float x, float y, float width, float height, String userData, boolean isSensor) {
        BodyDef bd = new BodyDef();
        bd.type = BodyDef.BodyType.KinematicBody;
        bd.position.set((x + width / 2f) / PPM, (y + height / 2f) / PPM);

        Body body = world.createBody(bd);
        PolygonShape ps = new PolygonShape();
        ps.setAsBox((width / 2f) / PPM, (height / 2f) / PPM);

        FixtureDef fd = new FixtureDef();
        fd.shape = ps;
        fd.isSensor = isSensor;
        fd.density = 0f;

        Fixture fx = body.createFixture(fd);
        fx.setUserData(userData);
        ps.dispose();
        return body;
    }

    private void spawnPipePair() {
        float pipeWidthPx = pipeTexture.getWidth();
        float gapSizePx = 0.25f * WORLD_HEIGHT;

        float minGapCenterY = gapSizePx / 2f + WORLD_HEIGHT * 0.05f;
        float maxGapCenterY = WORLD_HEIGHT - gapSizePx / 2f - WORLD_HEIGHT * 0.05f;
        float maxVerticalChange = WORLD_HEIGHT * 0.3f;

        float gapCenterYpx;
        if (lastGapCenterY < 0) {
            gapCenterYpx = minGapCenterY + (float) Math.random() * (maxGapCenterY - minGapCenterY);
        } else {
            float minY = Math.max(minGapCenterY, lastGapCenterY - maxVerticalChange);
            float maxY = Math.min(maxGapCenterY, lastGapCenterY + maxVerticalChange);
            gapCenterYpx = minY + (float) Math.random() * (maxY - minY);
        }

        float upperPipeYpx = gapCenterYpx + gapSizePx / 2f;
        float upperPipeHeightPx = WORLD_HEIGHT - upperPipeYpx;
        float lowerPipeHeightPx = gapCenterYpx - gapSizePx / 2f;

        float spawnX = WORLD_WIDTH + pipeWidthPx;

        PipePair pair = new PipePair();
        pair.upperPipeYpx = upperPipeYpx;
        pair.upperPipeHeightPx = upperPipeHeightPx;
        pair.lowerPipeHeightPx = lowerPipeHeightPx;

        pair.topBody = createKinematicBox(spawnX, upperPipeYpx, pipeWidthPx, upperPipeHeightPx, "pipe", true);
        pair.bottomBody = createKinematicBox(spawnX, 0f, pipeWidthPx, lowerPipeHeightPx, "pipe", true);

        float sensorWidthPx = 10f;
        float sensorHeightPx = gapSizePx;
        float sensorX = spawnX + pipeWidthPx / 2f - sensorWidthPx / 2f;
        float sensorY = gapCenterYpx - sensorHeightPx / 2f;
        pair.scoreSensor = createKinematicBox(sensorX, sensorY, sensorWidthPx, sensorHeightPx, "score", true);

        float speedMetersPerSec = pipeSpeedPx / PPM;
        pair.topBody.setLinearVelocity(-speedMetersPerSec, 0f);
        pair.bottomBody.setLinearVelocity(-speedMetersPerSec, 0f);
        pair.scoreSensor.setLinearVelocity(-speedMetersPerSec, 0f);

        pipePairs.add(pair);

        lastGapCenterY = gapCenterYpx;
    }

    private void stopAllPipes() {
        for (PipePair pair : pipePairs) {
            if (pair.topBody != null) pair.topBody.setLinearVelocity(0, 0);
            if (pair.bottomBody != null) pair.bottomBody.setLinearVelocity(0, 0);
            if (pair.scoreSensor != null) pair.scoreSensor.setLinearVelocity(0, 0);
        }
    }

    private void stopBird() {
        if (birdBody != null) birdBody.setLinearVelocity(0, 0);
    }

    private void stepWorld(float delta) {
        if (state == GameState.PAUSED) return;

        accumulator += Math.min(delta, 0.25f);
        while (accumulator >= TIME_STEP) {
            world.step(TIME_STEP, 8, 3);
            accumulator -= TIME_STEP;
        }
    }

    @Override
    public void render(float delta) {
        if (state == GameState.PLAYING) {
            timePassed += delta;
            pipeSpawnTimer += delta;
            pipeSpeedPx = Math.min(
                WORLD_WIDTH * (1f/8f) + WORLD_WIDTH * (1f/128f) * timePassed,
                maxPipeSpeedPx
            );
        }

        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (state == GameState.PLAYING) {
            float pipeSpawnInterval = Math.max(1f, pipeSpawnIntervalBase - 0.0025f * timePassed);
            if (pipeSpawnTimer >= pipeSpawnInterval) {
                pipeSpawnTimer = 0f;
                spawnPipePair();
            }
        }
        stepWorld(delta);

        Vector2 birdPos = birdBody.getPosition();
        float birdHalfHeight = (birdTexture.getHeight() / 2f) / PPM;

        if (birdPos.y - birdHalfHeight < 0) {
            birdBody.setTransform(birdPos.x, birdHalfHeight, 0);
            birdBody.setLinearVelocity(birdBody.getLinearVelocity().x, 0);
        }
        float worldTop = WORLD_HEIGHT / PPM;
        if (birdPos.y + birdHalfHeight > worldTop) {
            birdBody.setTransform(birdPos.x, worldTop - birdHalfHeight, 0);
            birdBody.setLinearVelocity(birdBody.getLinearVelocity().x, 0);
        }

        camera.update();
        batch.setProjectionMatrix(camera.combined);

        batch.begin();
        batch.draw(backgroundTexture, 0, 0, WORLD_WIDTH, WORLD_HEIGHT);
        batch.setColor(0f, 0f, 0f, 0.4f);
        batch.draw(overlayTexture, 0, 0, WORLD_WIDTH, WORLD_HEIGHT);

        batch.setColor(0.7f, 0.7f, 0.7f, 1f);
        for (PipePair pair : pipePairs) {
            float pipeXpx = pair.topBody.getPosition().x * PPM - pipeTexture.getWidth() / 2f;
            batch.draw(pipeTexture, pipeXpx, pair.upperPipeYpx, pipeTexture.getWidth(), pair.upperPipeHeightPx);
            batch.draw(pipeTexture, pipeXpx, 0, pipeTexture.getWidth(), pair.lowerPipeHeightPx);
        }
        batch.setColor(1f, 1f, 1f, 1f);
        float birdScale = 4f;
        Vector2 birdPosM = birdBody.getPosition();
        float birdDrawW = birdTexture.getWidth() * birdScale;
        float birdDrawH = birdTexture.getHeight() * birdScale;
        float birdDrawX = birdPosM.x * PPM - birdDrawW / 2f;
        float birdDrawY = birdPosM.y * PPM - birdDrawH / 2f;
        batch.draw(birdTexture, birdDrawX, birdDrawY, birdDrawW, birdDrawH);


        font.draw(batch, "SCORE: " + score, 40, WORLD_HEIGHT - 80);
        font.draw(batch, "HIGHSCORE: " + highScore, 40, WORLD_HEIGHT - 160);

        float btnX = WORLD_WIDTH - PAUSE_BUTTON_MARGIN - PAUSE_BUTTON_SIZE;
        float btnY = WORLD_HEIGHT - PAUSE_BUTTON_MARGIN - PAUSE_BUTTON_SIZE;

        batch.setColor(1f, 1f, 1f, 0.6f);
        batch.draw(buttonTexture, btnX, btnY, PAUSE_BUTTON_SIZE, PAUSE_BUTTON_SIZE);
        batch.setColor(1f, 1f, 1f, 1f);
        font.draw(batch, "| |", btnX + PAUSE_BUTTON_SIZE/2f - 25, btnY + PAUSE_BUTTON_SIZE/2f + 25);

        if (state == GameState.GAME_OVER) {
            if (!hasPlayedDeathSound) {
                deathSound.play();
                hasPlayedDeathSound = true;
            }
            drawCenteredText(batch, font, "GAME OVER", WORLD_HEIGHT / 2f + 100);
            drawCenteredText(batch, font, "TAP TO RESTART", WORLD_HEIGHT / 2f - 50);

        }
        if (state == GameState.PAUSED) {
            batch.setColor(1f, 1f, 1f, 0.5f);
            batch.draw(overlayTexture, 0, 0, WORLD_WIDTH, WORLD_HEIGHT);
            drawCenteredText(batch, font, "PAUSED", WORLD_HEIGHT / 2f + 100);
            drawCenteredText(batch, font, "TAP TO UNPAUSE", WORLD_HEIGHT / 2f - 50);

        }

        batch.end();
        handleInput();
        cleanupPipes();
    }


    private void handleInput() {
        if (Gdx.input.justTouched()) {
            Vector3 touch = new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0);
            viewport.unproject(touch);

            float tx = touch.x;
            float ty = touch.y;

            float btnX = WORLD_WIDTH - PAUSE_BUTTON_MARGIN - PAUSE_BUTTON_SIZE;
            float btnY = WORLD_HEIGHT - PAUSE_BUTTON_MARGIN - PAUSE_BUTTON_SIZE;

            boolean touchOnPauseButton = tx >= btnX && tx <= btnX + PAUSE_BUTTON_SIZE && ty >= btnY && ty <= btnY + PAUSE_BUTTON_SIZE;

            if (state == GameState.GAME_OVER) {
                resetGame();
                return;
            }

            if (state == GameState.PAUSED) {
                exitPause();
                return;
            }

            if (touchOnPauseButton) {
                enterPause();
                return;
            }

            birdBody.setLinearVelocity(birdBody.getLinearVelocity().x, jumpVelocityMps);
            flapSound.play();
        }
    }

    private void cleanupPipes() {
        Iterator<PipePair> it = pipePairs.iterator();
        while (it.hasNext()) {
            PipePair p = it.next();
            float centerXPx = p.bottomBody.getPosition().x * PPM;
            if (centerXPx + pipeTexture.getWidth() < -50) {
                if (p.topBody != null) { world.destroyBody(p.topBody); p.topBody = null; }
                if (p.bottomBody != null) { world.destroyBody(p.bottomBody); p.bottomBody = null; }
                if (p.scoreSensor != null) { world.destroyBody(p.scoreSensor); p.scoreSensor = null; }
                it.remove();
            }
        }
    }

    private void resetGame() {
        for (PipePair p : pipePairs) {
            if (p.topBody != null) world.destroyBody(p.topBody);
            if (p.bottomBody != null) world.destroyBody(p.bottomBody);
            if (p.scoreSensor != null) world.destroyBody(p.scoreSensor);
        }
        pipePairs.clear();
        lastGapCenterY = -1f;

        birdBody.setTransform(birdStartPx.x / PPM, birdStartPx.y / PPM, 0f);
        birdBody.setLinearVelocity(0f, 0f);

        timePassed = 0f;
        pipeSpawnTimer = 0f;
        score = 0;
        state = GameState.PLAYING;
        hasPlayedDeathSound = false;
    }

    private void enterPause() {
        if (state != GameState.PLAYING)
            return;
        prevBirdVel.set(birdBody.getLinearVelocity());
        for (PipePair p : pipePairs) {
            if (p.topBody != null) p.prevTopVel.set(p.topBody.getLinearVelocity());
            if (p.bottomBody != null) p.prevBottomVel.set(p.bottomBody.getLinearVelocity());
            if (p.scoreSensor != null) p.prevSensorVel.set(p.scoreSensor.getLinearVelocity());
        }
        birdBody.setLinearVelocity(0,0);
        stopAllPipes();
        state = GameState.PAUSED;
    }

    private void exitPause() {
        if (state != GameState.PAUSED)
            return;
        birdBody.setLinearVelocity(prevBirdVel);
        for (PipePair p : pipePairs) {
            if (p.topBody != null) p.topBody.setLinearVelocity(p.prevTopVel);
            if (p.bottomBody != null) p.bottomBody.setLinearVelocity(p.prevBottomVel);
            if (p.scoreSensor != null) p.scoreSensor.setLinearVelocity(p.prevSensorVel);
        }
        state = GameState.PLAYING;
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void pause() {
        if (state == GameState.PLAYING)
            enterPause();
    }

    @Override
    public void resume() {}

    @Override
    public void hide() {}

    @Override
    public void dispose() {
        for (PipePair p : pipePairs) {
            if (p.topBody != null) world.destroyBody(p.topBody);
            if (p.bottomBody != null) world.destroyBody(p.bottomBody);
            if (p.scoreSensor != null) world.destroyBody(p.scoreSensor);
        }
        pipePairs.clear();

        if (birdBody != null) world.destroyBody(birdBody);

        world.dispose();
        batch.dispose();
        birdTexture.dispose();
        pipeTexture.dispose();
        font.dispose();
        deathSound.dispose();
        scoreSound.dispose();


        if (overlayTexture != null) overlayTexture.dispose();
        if (buttonTexture != null) buttonTexture.dispose();
    }
    private void drawCenteredText(SpriteBatch batch, BitmapFont font, String text, float y) {
        layout.setText(font, text);
        float x = (WORLD_WIDTH - layout.width) / 2f;
        font.draw(batch, layout, x, y);
    }

}

