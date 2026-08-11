package com.codex.uvcrecorder;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RecordingController {
    private static final String TAG = "RecordingController";
    private static final ExecutorService CONTROL_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "uvc-recording-control");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    interface Listener {
        void onRecordingState(boolean mainActive, boolean auxActive, long mainDurationMs,
                              long auxDurationMs);

        void onRecordingSaved(String displayName);

        void onRecordingWarning(RecordingEntry.Channel channel, String message);

        void onRecordingError(RecordingEntry.Channel channel, String message, Throwable error);
    }

    private final Context context;
    private final UvcSurfaceSource cameraSource;
    private final SignalInfo signal;
    private final Listener listener;
    private final String deviceTag;
    private final boolean allowAux;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ChannelState main = new ChannelState(RecordingEntry.Channel.MAIN);
    private final ChannelState aux = new ChannelState(RecordingEntry.Channel.AUX);
    private boolean released;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            rotateIfNeeded(main);
            rotateIfNeeded(aux);
            notifyState();
            handler.postDelayed(this, 500);
        }
    };

    RecordingController(Context context, UvcSurfaceSource cameraSource, SignalInfo signal,
                        Listener listener) {
        this(context, cameraSource, signal, listener, null, true);
    }

    RecordingController(Context context, UvcSurfaceSource cameraSource, SignalInfo signal,
                        Listener listener, String deviceTag, boolean allowAux) {
        this.context = context.getApplicationContext();
        this.cameraSource = cameraSource;
        this.signal = signal;
        this.listener = listener;
        this.deviceTag = deviceTag;
        this.allowAux = allowAux;
        handler.post(ticker);
    }

    void toggleMain() {
        if (main.desiredActive) stopMain();
        else startMain();
    }

    void startMain() {
        if (released || main.desiredActive) return;
        main.desiredActive = true;
        main.sessionStartMs = SystemClock.elapsedRealtime();
        startChannel(main);
        if (main.desiredActive && allowAux && AppSettings.isDualEnabled(context)) {
            aux.desiredActive = true;
            aux.sessionStartMs = main.sessionStartMs;
            startChannel(aux);
        }
        notifyState();
    }

    void stopMain() {
        stopChannel(main);
        stopChannel(aux);
        notifyState();
    }

    void toggleAux() {
        if (!allowAux || !main.desiredActive || !AppSettings.isDualEnabled(context)) return;
        if (aux.desiredActive) {
            stopChannel(aux);
        } else {
            aux.desiredActive = true;
            aux.sessionStartMs = SystemClock.elapsedRealtime();
            startChannel(aux);
        }
        notifyState();
    }

    boolean isMainActive() {
        return main.desiredActive;
    }

    boolean isAuxActive() {
        return aux.desiredActive;
    }

    boolean isAnythingActive() {
        return main.desiredActive || aux.desiredActive || main.recorder != null || aux.recorder != null;
    }

    void release() {
        released = true;
        handler.removeCallbacks(ticker);
        stopChannel(main);
        stopChannel(aux);
    }

    private void startChannel(ChannelState state) {
        if (released || !state.desiredActive || state.recorder != null || state.starting) return;
        state.starting = true;
        int request = ++state.generation;
        CONTROL_EXECUTOR.execute(() -> prepareChannel(state, request));
    }

    private void prepareChannel(ChannelState state, int request) {
        RecordingTarget target = null;
        try {
            AppSettings.Container container = AppSettings.getContainer(context);
            target = FileRepository.createTarget(context, state.channel, container, deviceTag);
            int configured = AppSettings.getBitrateMbps(context);
            int bitRate = configured > 0 ? configured * 1_000_000 : automaticBitRate();
            int outputWidth = cameraSource.getRecordingWidth(signal.width, signal.height);
            int outputHeight = cameraSource.getRecordingHeight(signal.width, signal.height);
            SurfaceRecorder recorder = new SurfaceRecorder(cameraSource, target, outputWidth,
                    outputHeight, signal.fps, bitRate, AppSettings.getVideoCodec(context),
                    AppSettings.isUsbAudioEnabled(context), new SurfaceRecorder.Callback() {
                @Override
                public void onFinished(SurfaceRecorder recorder, RecordingTarget completed,
                                       Throwable error) {
                    handleFinished(state, request, recorder, completed, error);
                }
            });
            RecordingTarget preparedTarget = target;
            handler.post(() -> registerPreparedRecorder(
                    state, request, recorder, preparedTarget));
        } catch (Throwable error) {
            Log.e(TAG, "Failed to start " + state.channel + " recording", error);
            if (target != null) target.cancel(context);
            handler.post(() -> handleStartFailure(state, request, null, error));
        }
    }

    private void registerPreparedRecorder(ChannelState state, int request,
                                          SurfaceRecorder recorder,
                                          RecordingTarget target) {
        if (released || !state.desiredActive || state.generation != request) {
            if (state.generation == request) state.starting = false;
            CONTROL_EXECUTOR.execute(() -> target.cancel(context));
            notifyState();
            return;
        }
        state.recorder = recorder;
        CONTROL_EXECUTOR.execute(() -> {
            try {
                recorder.start();
                handler.post(() -> handleStartSuccess(state, request, recorder));
            } catch (Throwable error) {
                Log.e(TAG, "Failed to initialize " + state.channel + " encoder", error);
                target.cancel(context);
                handler.post(() -> handleStartFailure(state, request, recorder, error));
            }
        });
    }

    private void handleStartSuccess(ChannelState state, int request,
                                    SurfaceRecorder recorder) {
        if (state.recorder != recorder) return;
        state.starting = false;
        if (released || !state.desiredActive || state.generation != request) {
            CONTROL_EXECUTOR.execute(recorder::stop);
            notifyState();
            return;
        }
        state.segmentStartMs = SystemClock.elapsedRealtime();
        if (recorder.getStartWarning() != null) {
            listener.onRecordingWarning(state.channel, recorder.getStartWarning());
        }
        notifyState();
    }

    private void handleStartFailure(ChannelState state, int request,
                                    SurfaceRecorder recorder, Throwable error) {
        if (recorder != null && state.recorder != recorder) return;
        if (state.recorder == recorder) state.recorder = null;
        state.starting = false;
        if (state.generation == request) {
            state.desiredActive = false;
            if (state.channel == RecordingEntry.Channel.MAIN) stopChannel(aux);
            if (!released) {
                listener.onRecordingError(state.channel, readableMessage(error), error);
            }
        }
        notifyState();
    }

    private void handleFinished(ChannelState state, int request,
                                SurfaceRecorder recorder, RecordingTarget completed,
                                Throwable error) {
        if (state.recorder != recorder) return;
        state.recorder = null;
        state.starting = false;
        if (!released) {
            if (error == null) {
                listener.onRecordingSaved(completed.displayName);
            } else {
                if (state.generation == request) {
                    state.desiredActive = false;
                    if (state.channel == RecordingEntry.Channel.MAIN) stopChannel(aux);
                }
                listener.onRecordingError(state.channel, readableMessage(error), error);
            }
            if (state.desiredActive && state.generation == request) startChannel(state);
            notifyState();
        }
    }

    private void stopChannel(ChannelState state) {
        state.desiredActive = false;
        state.starting = false;
        state.generation++;
        SurfaceRecorder recorder = state.recorder;
        if (recorder != null) CONTROL_EXECUTOR.execute(recorder::stop);
    }

    private void rotateIfNeeded(ChannelState state) {
        int minutes = AppSettings.getSegmentMinutes(context);
        if (minutes <= 0 || !state.desiredActive || state.recorder == null || state.segmentStartMs == 0) {
            return;
        }
        long segmentMs = minutes * 60_000L;
        if (SystemClock.elapsedRealtime() - state.segmentStartMs >= segmentMs) {
            state.segmentStartMs = 0;
            SurfaceRecorder recorder = state.recorder;
            CONTROL_EXECUTOR.execute(recorder::stop);
        }
    }

    private int automaticBitRate() {
        long rate = Math.round(signal.width * (double) signal.height * signal.fps * 0.10);
        return (int) Math.max(5_000_000L, Math.min(120_000_000L, rate));
    }

    private void notifyState() {
        long now = SystemClock.elapsedRealtime();
        long mainDuration = main.desiredActive ? Math.max(0, now - main.sessionStartMs) : 0;
        long auxDuration = aux.desiredActive ? Math.max(0, now - aux.sessionStartMs) : 0;
        listener.onRecordingState(main.desiredActive, aux.desiredActive, mainDuration, auxDuration);
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message;
    }

    private static final class ChannelState {
        final RecordingEntry.Channel channel;
        SurfaceRecorder recorder;
        boolean desiredActive;
        boolean starting;
        int generation;
        long sessionStartMs;
        long segmentStartMs;

        ChannelState(RecordingEntry.Channel channel) {
            this.channel = channel;
        }
    }
}
