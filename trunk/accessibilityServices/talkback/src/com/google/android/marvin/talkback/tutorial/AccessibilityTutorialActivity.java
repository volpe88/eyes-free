/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.marvin.talkback.tutorial;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.widget.ViewAnimator;

import com.google.android.marvin.talkback.FullScreenReadController;
import com.google.android.marvin.talkback.R;
import com.google.android.marvin.talkback.SpeechController;
import com.google.android.marvin.talkback.SpeechController.UtteranceCompleteRunnable;
import com.google.android.marvin.talkback.TalkBackService;
import com.google.android.marvin.talkback.TalkBackService.ServiceState;
import com.google.android.marvin.talkback.TalkBackService.ServiceStateListener;
import com.googlecode.eyesfree.utils.FeedbackController;
import com.googlecode.eyesfree.utils.LogUtils;
import com.googlecode.eyesfree.utils.WeakReferenceHandler;

/**
 * This class provides a short tutorial that introduces the user to the features
 * available in Touch Exploration.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class AccessibilityTutorialActivity extends Activity {
    /** This processor requires JellyBean (API 16). */
    public static final int MIN_API_LEVEL = Build.VERSION_CODES.JELLY_BEAN;

    /** Instance state saving constant for the active module. */
    private static final String KEY_ACTIVE_MODULE = "active_module";

    /** The index of the module to show when first opening the tutorial. */
    private static final int DEFAULT_MODULE = 0;

    /** Whether or not the tutorial is active. */
    private static boolean sTutorialIsActive = false;

    /** Whether or not to allow the service to show context menus. */
    private static boolean sAllowContextMenus = true;

    private static final int REPEAT_DELAY = 15000;
    private static final int RESUME_REPEAT_DELAY = 1500;

    private static final int TRIGGER_SOUND_PROGRAM = 2;
    private static final int TRIGGER_SOUND_VELOCITY = 127;
    private static final int TRIGGER_SOUND_DURATION = 52;
    private static final int TRIGGER_SOUND_STARTING_PITCH = 58;
    private static final int TRIGGER_SOUND_PITCHES_TO_PLAY = 4;
    private static final int TRIGGER_SOUND_SCALE_TYPE = FeedbackController.MIDI_SCALE_TYPE_MAJOR;

    /** View animator for switching between modules. */
    private ViewAnimator mViewAnimator;

    private AccessibilityManager mAccessibilityManager;
    private RepeatHandler mRepeatHandler;
    private FeedbackController mFeedbackController;

    private Bundle mSavedInstanceState;
    private int mResourceIdToRepeat = 0;
    private Object[] mRepeatedFormatArgs;
    private boolean mOrientationLocked = false;

    /** Flag set by onCreate to identify the following call to onResume. */
    private boolean mFirstTimeResume = false;

    private final OnCancelListener mFinishActivityOnCancelListener = new OnCancelListener() {
        @Override
        public void onCancel(DialogInterface dialog) {
            finish();
        }
    };

    private final OnClickListener mFinishActivityOnClickListener = new OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            finish();
        }
    };

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSavedInstanceState = savedInstanceState;

        final Animation inAnimation = AnimationUtils.loadAnimation(this,
                android.R.anim.slide_in_left);
        inAnimation.setAnimationListener(mInAnimationListener);

        final Animation outAnimation = AnimationUtils.loadAnimation(this,
                android.R.anim.slide_in_left);

        mRepeatHandler = new RepeatHandler(this);

        mFeedbackController = new FeedbackController(this);

        mViewAnimator = new ViewAnimator(this);
        mViewAnimator.setInAnimation(inAnimation);
        mViewAnimator.setOutAnimation(outAnimation);
        mViewAnimator.addView(new TouchTutorialModule1(this));
        mViewAnimator.addView(new TouchTutorialModule2(this));
        mViewAnimator.addView(new TouchTutorialModule3(this));
        mViewAnimator.addView(new TouchTutorialModule4(this));

        // Module 5 (text editing) requires JellyBean MR2 (API 18) features.
        if (Build.VERSION.SDK_INT >= TouchTutorialModule5.MIN_API_LEVEL) {
            mViewAnimator.addView(new TouchTutorialModule5(this));
        }

        // Ensure the screen stays on and doesn't change orientation.
        final Window window = getWindow();
        final WindowManager.LayoutParams params = window.getAttributes();
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
        params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        window.setAttributes(params);

        setContentView(mViewAnimator);

        mAccessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);

        // Lock the screen orientation until the first instruction is read.
        lockOrientation();

        mFirstTimeResume = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        sTutorialIsActive = false;

        final TalkBackService service = TalkBackService.getInstance();
        if (service != null) {
            service.removeServiceStateListener(mServiceStateListener);
        }

        getCurrentModule().onPause();

        interrupt();

        // This is different than stopRepeating because we want the current
        // instruction text to continue repeating if the activity resumes.
        mRepeatHandler.removeMessages(RepeatHandler.MSG_REPEAT);

        unlockOrientation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sTutorialIsActive = true;

        /*
         * Handle the cases where the tutorial was started with TalkBack in an
         * invalid state (inactive, suspended, or without Explore by Touch
         * enabled).
         */
        final ServiceState serviceState = TalkBackService.getServiceState();
        /*
         * Check for suspended state first because touch exploration reports it
         * is disabled when TalkBack is suspended.
         */
        if (serviceState == ServiceState.SUSPENDED) {
            showAlertDialogAndFinish(R.string.accessibility_tutorial_service_suspended_title,
                    R.string.accessibility_tutorial_service_suspended_message);
            return;
        } else if ((serviceState == ServiceState.INACTIVE)
                || !mAccessibilityManager.isTouchExplorationEnabled()) {
            showAlertDialogAndFinish(R.string.accessibility_tutorial_service_inactive_title,
                    R.string.accessibility_tutorial_service_inactive_message);
            return;
        }

        final TalkBackService service = TalkBackService.getInstance();
        service.addServiceStateListener(mServiceStateListener);

        if (mFirstTimeResume) {
            mFirstTimeResume = false;

            if (mSavedInstanceState != null) {
                show(mSavedInstanceState.getInt(KEY_ACTIVE_MODULE, DEFAULT_MODULE));
            } else {
                show(DEFAULT_MODULE);
            }
        }

        getCurrentModule().onResume();

        if (mResourceIdToRepeat > 0) {
            mRepeatHandler.sendEmptyMessageDelayed(RepeatHandler.MSG_REPEAT, RESUME_REPEAT_DELAY);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(KEY_ACTIVE_MODULE, mViewAnimator.getDisplayedChild());
    }

    public static boolean isTutorialActive() {
        return sTutorialIsActive;
    }

    public static boolean shouldAllowContextMenus() {
        return sAllowContextMenus;
    }

    protected static void setAllowContextMenus(boolean allowed) {
        sAllowContextMenus = allowed;
    }

    public TutorialModule getCurrentModule() {
        final View currentView = mViewAnimator.getCurrentView();
        if (currentView == null || !(currentView instanceof TutorialModule)) {
            throw new IllegalStateException("Current view is not a valid TutorialModule.");
        }

        return (TutorialModule) currentView;
    }

    protected void next() {
        show(mViewAnimator.getDisplayedChild() + 1);
    }

    protected void previous() {
        show(mViewAnimator.getDisplayedChild() - 1);
    }

    private void show(int which) {
        if ((which < 0) || (which >= mViewAnimator.getChildCount())) {
            LogUtils.log(this, Log.WARN, "Tried to show a module with an index out of bounds.");
            return;
        }

        if (which != mViewAnimator.getDisplayedChild()) {
            // Interrupt speech and stop the previous module.
            mAccessibilityManager.interrupt();
            interrupt();
            stopRepeating();
            mViewAnimator.setOnKeyListener(null);
            getCurrentModule().onPause();
            getCurrentModule().onStop();
        }

        mViewAnimator.setDisplayedChild(which);
    }

    public void setTouchGuardActive(boolean active) {
        final View touchGuard = getCurrentModule().findViewById(R.id.touch_guard);

        if (active) {
            touchGuard.setVisibility(View.VISIBLE);
        } else {
            touchGuard.setVisibility(View.GONE);
        }
    }

    /**
     * Plays a sound indicating that the user has activated a trigger in the
     * tutorial.
     */
    public void playTriggerSound() {
        mFeedbackController.playMidiScale(TRIGGER_SOUND_PROGRAM, TRIGGER_SOUND_VELOCITY,
                TRIGGER_SOUND_DURATION, TRIGGER_SOUND_STARTING_PITCH, TRIGGER_SOUND_PITCHES_TO_PLAY,
                TRIGGER_SOUND_SCALE_TYPE);
    }

    /** Locks the framework orientation to the current device orientation. */
    public void lockOrientation() {
        if (mOrientationLocked) {
            return;
        }

        mOrientationLocked = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            setRequestedOrientation(calculateCurrentScreenOrientation());
        }
    }

    /** Unlocks the framework orientation so it can change on device rotation. */
    public void unlockOrientation() {
        if (!mOrientationLocked) {
            return;
        }

        mOrientationLocked = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private int calculateCurrentScreenOrientation() {
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        boolean isReversed = displayRotation >= 180;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return isReversed ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            if (displayRotation == 90 || displayRotation == 270) {
                /*
                 * If displayRotation = 90 or 270, then we are on a landscape
                 * device. On landscape devices, portrait is a 90 degree
                 * clockwise rotation from landscape, so we need to flip which
                 * portrait we pick, as display rotation is counter clockwise.
                 */
                isReversed = !isReversed;
            }

            return isReversed ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    protected SpeechController getSpeechController() {
        final TalkBackService service = TalkBackService.getInstance();
        if (service == null) {
            LogUtils.log(Log.ERROR, "Failed to get TalkBackService instance.");
            return null;
        }

        return service.getSpeechController();
    }

    protected FullScreenReadController getFullScreenReadController() {
        final TalkBackService service = TalkBackService.getInstance();
        if (service == null) {
            LogUtils.log(Log.ERROR, "Failed to get TalkBackService instance.");
            return null;
        }

        return service.getFullScreenReadController();
    }

    /**
     * Speaks a new tutorial instruction.
     *
     * @param resId The resource value of the instruction string.
     * @param repeat Whether the instruction should be repeated indefinitely.
     * @param formatArgs Optional formatting arguments.
     * @see String#format(String, Object...)
     */
    public void speakInstruction(int resId, boolean repeat, Object... formatArgs) {
        stopRepeating();

        lockOrientation();
        setTouchGuardActive(true);

        speakInternal(resId, formatArgs);

        if (repeat) {
            mResourceIdToRepeat = resId;
            mRepeatedFormatArgs = formatArgs;
        } else {
            mResourceIdToRepeat = 0;
        }
    }

    /**
     * Speaks the previously stored instruction text again.
     * <p>
     * Assumes that {@code mTextToRepeat} is non-null.
     */
    private void repeatInstruction() {
        if (!sTutorialIsActive) {
            mRepeatHandler.removeMessages(RepeatHandler.MSG_REPEAT);
            return;
        }

        lockOrientation();
        setTouchGuardActive(true);

        speakInternal(mResourceIdToRepeat, mRepeatedFormatArgs);
    }

    /**
     * Stops the current instruction from repeating in the future.
     * <p>
     * Note: this carries over even after the activity is paused and resumed.
     */
    public void stopRepeating() {
        mRepeatHandler.removeMessages(RepeatHandler.MSG_REPEAT);
        mResourceIdToRepeat = 0;
    }

    /**
     * Sends the instruction text to the speech controller for queuing.
     *
     * @param resId The resource value of the instruction string.
     * @param formatArgs Optional formatting arguments.
     * @see String#format(String, Object...)
     */
    private void speakInternal(int resId, Object... formatArgs) {
        final TalkBackService service = TalkBackService.getInstance();
        if (service == null) {
            LogUtils.log(Log.ERROR, "Failed to get TalkBackService instance.");
            return;
        }

        final SpeechController speechController = service.getSpeechController();
        final String text = getString(resId, formatArgs);
        speechController.speak(text, null, null, 0, 0, null, null, mUtteranceCompleteRunnable);
    }

    /** Interrupts the speech controller. */
    private void interrupt() {
        final TalkBackService service = TalkBackService.getInstance();
        if (service == null) {
            LogUtils.log(Log.ERROR, "Failed to get TalkBackService instance.");
            return;
        }

        final SpeechController speechController = service.getSpeechController();
        speechController.interrupt();
    }

    /**
     * Handles when the speech controller finished speaking an instruction.
     *
     * @param status The speech item status code from the speech controller.
     */
    private void onUtteranceComplete(int status) {
        setTouchGuardActive(false);
        mFeedbackController.playSound(R.raw.ready, 1.0f, 1.0f);
        unlockOrientation();

        if (sTutorialIsActive && (mResourceIdToRepeat > 0)) {
            mRepeatHandler.sendEmptyMessageDelayed(RepeatHandler.MSG_REPEAT, REPEAT_DELAY);
        }
    }

    protected void showAlertDialogAndFinish(int titleId, int messageId) {
        showAlertDialogAndFinish(getString(titleId), getString(messageId));
    }

    protected void showAlertDialogAndFinish(String title, String message) {
        interrupt();
        stopRepeating();

        new AlertDialog.Builder(AccessibilityTutorialActivity.this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setOnCancelListener(mFinishActivityOnCancelListener)
            .setPositiveButton(R.string.accessibility_tutorial_alert_dialog_exit,
                    mFinishActivityOnClickListener)
            .create()
            .show();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    private final AnimationListener mInAnimationListener = new AnimationListener() {
        @Override
        public void onAnimationEnd(Animation animation) {
            getCurrentModule().onStart();
            getCurrentModule().onResume();
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
            // Do nothing.
        }

        @Override
        public void onAnimationStart(Animation animation) {
            // Do nothing.
        }
    };

    /** A wrapper for code to run when an instruction finishes speaking. */
    private final UtteranceCompleteRunnable mUtteranceCompleteRunnable =
            new UtteranceCompleteRunnable() {
         @Override
         public void run(int status) {
             onUtteranceComplete(status);
         }
     };

    private final ServiceStateListener mServiceStateListener = new ServiceStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState newState) {
            if (newState == ServiceState.INACTIVE) {
                // If the service dies while the tutorial is active, exit.
                finish();
            } else if (newState == ServiceState.SUSPENDED) {
                stopRepeating();

                // If the service is suspended, show an alert and exit.
                showAlertDialogAndFinish(R.string.accessibility_tutorial_service_suspended_title,
                        R.string.accessibility_tutorial_service_suspended_message);
            }
        }
    };

    /** A handler for repeating tutorial instruction text. */
    private static class RepeatHandler extends WeakReferenceHandler<AccessibilityTutorialActivity> {
        private static final int MSG_REPEAT = 1;

        public RepeatHandler(AccessibilityTutorialActivity parent) {
            super(parent);
        }

        @Override
        public void handleMessage(Message msg, AccessibilityTutorialActivity parent) {
            switch (msg.what) {
                case MSG_REPEAT:
                    parent.repeatInstruction();
                    break;
            }
        }
    }
}
