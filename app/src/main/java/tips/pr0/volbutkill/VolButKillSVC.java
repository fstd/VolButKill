package tips.pr0.volbutkill;

/*
 * (License terms at the bottom (tl;dr: 3-clause BSD))
 *
 * This is VolButKill, essentially elesbb's "Hold Back to Kill", but worse.
 * The upside is, it works for devices that do not have a physical back button.
 * The downside is, it does not use the back button, physical or not.
 * Instead, the volume buttons are abused to achieve a similar purpose.
 *
 * The procedure is a bit more involved and less intuitive than in HBTK:
 *
 * 1. Press both volume buttons at approximately the same time (within 250ms
 *    of one another).  Release both buttons.  This "arms" the kill logic;
 *    the phone will confirm it with a brief vibration and a toast message.
 *
 * 2. For the next 10 seconds, both volume buttons are assigned new functions:
 *    - Holding volume up for 1 second will kill the foreground app
 *    - Pressing volume down will disarm the kill logic early and restore
 *      normal behavior
 *
 * The 10 second timespan is chosen for phones that produce some action for
 * pressing both volume buttons (like older LG phones launching QuickMemo),
 * so there is enough time to get the to-be-killed app back to the foreground.
 *
 * It was considered to avoid the whole arm/disarm logic and kill on holding
 * both buttons for a set duration, but that makes it difficult to quickly
 * put away any QuickMemo-like apps popping up, and it also activates some
 * accessibility feature like talkback on other phones.
 *
 * It was considered to kill upon, say, holding volume up for a while and
 * then tapping volume down, or vice versa.  But that sucks as well, nobody
 * wants their ring volume changed every time a misbehaving app needs to go.
 *
 * Therefore, arm-then-kill it is.
 *
 * Notes and caveats:
 *
 * - The minimum required API level for VolButKill is 18, corresponding to
 *   Android 4.3.x (Jelly Bean).  It has been confirmed to work
 *   on 4.4 (KitKat) and 8.0.0 (Oreo).  Feel free to report back other
 *   versions you've tested it on.
 *
 * - All the delays, as well as the button sequences are hardwired; there is
 *   no configuration-like activity.  This is /just/ a naked accessibility
 *   service.  After installing, it has to be enabled in the accessibility
 *   submenu inside the phone settings before it will work.
 *
 * - It (obviousy) requires the phone to be rooted.  It has been tested with
 *   Magisk as well as with an ancient version of SuperSU; it should work with
 *   anything that provides a 'su' and a 'killall' program.
 *
 * - VolButKill will try to avoid killing the "Home" screen.  If the app name
 *   of your launcher is not "Home", that will not work.  Feel free to drop
 *   me an email to get your launcher app added to the do-not-kill list.
 *
 * - VolButKill will try to avoid killing keyboard apps.  Due to the way the
 *   foreground app detection works, if the foreground app has its focus on
 *   a widget that causes a keyboard to be displayed (like a text input field),
 *   the actual foreground app is actually the keyboard itself, so what would
 *   be killed is the keyboard, not the app itself.  In order to avoid this
 *   situation, VolButKill will ignore any app that has the string 'keyboard'
 *   in its package name.  There probably exists keyboards with offball package
 *   names that will be missed; if you encounter your keyboard being killed
 *   drop me an email to add your keyboard app to the do-not-kill list.
 *   "Hold Back to Kill" did not suffer from this issue because it detected
 *   the foreground app in a way that Android no longer supports as of more
 *   recent versions.
 *
 * - VolButKill is implemented as an accessibility service, but does not
 *   actually provide accessibility features.  This is a big Google No-No.
 *   For this reason it will never be allowed on the Google Play Store.
 *   Any attempt to submit it will only fuel Google's hate boner for people
 *   using accessibility services for features they weren't designed to do
 *   and make life more difficult for everybody doing this.
 *
 * - VolButKill is Free and Open Source Software, licensed under the 3-clause
 *   BSD License.  Feel free to copy/modify/share/whatever VolButKill as
 *   long as you credit the original author and keep the license terms intact.
 *   Look at the bottom of this file for details.
 *
 * - I don't usually write Android apps, and for all I know I did every single
 *   thing wrong and violated every design and programming guideline in
 *   existence.  If you're an experienced/knowledgable Android programmer and
 *   reading the source, feel free to report things that seem off/wrong.
 *
 * - You can contact me at <fstd+vbk@pr0.tips> or on #fstd on irc.freenode.org
 *
 * (C) 2020 Timo Buhrmester
 */

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

public class VolButKillSVC extends AccessibilityService {

	/* arm if both volkeys are pressed within EPS ms of one another */
	private final long EPS = 250;

	/* disarm after DISARMTIME ms of being armed */
	private final long DISARMTIME = 10000;

	/* kill if, while armed, vol up is held down HOLDTIME ms */
	private final long HOLDTIME = 1000;

	private static final String TAG = "VolButKillSVC"; /* logcat tag */

	private long volDnAt = 0; /* time of voldown being pressed */
	private long volUpAt = 0; /* time of volup being pressed */
	private long armedAt = 0; /* time of being armed */
	private boolean killing = false; /* remembers if a kill was scheduled */

	private boolean volDnIsPressed = false;
	private boolean volUpIsPressed = false;

	private boolean consumeNextVolUpUp = false;
	private boolean consumeNextVolDnUp = false;

	private String curFgProc = ""; /* package name of current foreground app */


	private final Object lock = new Object();
	private Killer killer = null;
	private Handler mHandler = new Handler();

	private class Killer extends Thread {
		private String procName;
		private final Object lock;

		private Killer(String procName, Object lock) {
			this.procName = procName;
			this.lock = lock;
		}

		public void run() {
			try {
				String appName = getPackageManager().getApplicationLabel(getPackageManager()
				  .getApplicationInfo(procName, 0)).toString();

				if (appName.equals("Home")) {
					Log.d(TAG, "Not killing " + procName + " (Home)");
					return;
				}
				Runtime.getRuntime().exec("su -c killall " + procName);
				vibe();
				toast("Killed: " + appName);
			}
			catch (Exception e) {
				Log.e(TAG, "caught " + e.toString(), e);
			}

			synchronized(this.lock) {
				VolButKillSVC.this.killing = false;
			}
		}
	}

	public VolButKillSVC() {
	}

	private void vibe() {
		Vibrator v = ((Vibrator)getSystemService(Context.VIBRATOR_SERVICE));
		if (v != null)
			v.vibrate(50);
	}

	private void toast(String msg) {
		Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onInterrupt() {}


	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
		if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
			if (event.getPackageName() != null) {
				try {
					ApplicationInfo ai = getPackageManager().getApplicationInfo(
					  event.getPackageName().toString(), 0);
					if (ai.processName.equals("com.android.systemui")) {
						/*
						 * quirk: this is the volume change popup, we do /not/
						 * get a window change notification back to the original
						 * app after it goes away.  might be a bug in android.
						 * anyway, ignore it.
						 */
						Log.d(TAG, "ignoring: " + ai.processName);
					} else if (ai.processName.toLowerCase().contains("keyboard")) {
						/*
						 * likewise, we usually don't want to kill the keyboard
						 * but the app that's using the keyboard
						 */
						Log.d(TAG, "ignoring: " + ai.processName);
					} else {
						curFgProc = ai.processName;
						Log.d(TAG, "foreground windows is now: " + curFgProc);
					}
				} catch (Exception e) {
					Log.e(TAG, "caught " + e.toString(), e);
				}
			}
		}
	}

	@Override
	protected boolean onKeyEvent(KeyEvent event) {
		if (event.getKeyCode() != KEYCODE_VOLUME_DOWN && event.getKeyCode() != KEYCODE_VOLUME_UP)
			return false;

		Log.d(TAG, "KeyEvent: " + event.toString());
		trackButtonState(event);
		boolean b = process(event);

		Log.d(TAG, "KeyEvent " + (b?"consumed":"propagated"));
		return b;
	}

	private void trackButtonState(KeyEvent event) {
		if (event.getKeyCode() == KEYCODE_VOLUME_DOWN) {
			volDnIsPressed = event.getAction() == ACTION_DOWN;
		} else if (event.getKeyCode() == KEYCODE_VOLUME_UP) {
			volUpIsPressed = event.getAction() == ACTION_DOWN;
		}
	}

	private boolean process(KeyEvent event) {
		boolean consume = false; /* track whether this event is to be consumed */

		if (armedAt > 0 && event.getEventTime() - armedAt > DISARMTIME) {
			Log.d(TAG, "disarmed");
			armedAt = 0;
		}

		if (armedAt == 0) {
			if (event.getAction() == ACTION_DOWN) {
				if (event.getKeyCode() == KEYCODE_VOLUME_UP)
					volUpAt = event.getEventTime();

				if (event.getKeyCode() == KEYCODE_VOLUME_DOWN)
					volDnAt = event.getEventTime();

				if (volUpAt > 0 && volDnAt > 0 && Math.abs(volUpAt - volDnAt) < EPS) {
					Log.d(TAG, "armed");
					armedAt = event.getEventTime();
					toast("Armed ("+(DISARMTIME/1000)+"s): hold VolUp to kill");
					vibe();
				}
			}
		} else {

			/* disarm with volume-down while armed. */
			if (event.getKeyCode() == KEYCODE_VOLUME_DOWN && event.getAction() == ACTION_DOWN
			  && !volUpIsPressed) {
				armedAt = 0;
				toast("Disarmed");
				consume = true;
			}

			if (event.getKeyCode() == KEYCODE_VOLUME_UP) {
				if (event.getAction() == ACTION_DOWN) {
					if (curFgProc.length() == 0) {
						Log.w(TAG, "we don't know what to kill");
					} else {
						Log.d(TAG, "scheduling death of " + curFgProc);
						synchronized (lock) {
							killing = true;
						}

						mHandler.postDelayed(killer = new Killer(curFgProc, lock), HOLDTIME);
						consume = true;
					}
				} else if (event.getAction() == ACTION_UP) {
					boolean canc = false;
					synchronized (lock) {
						if (killing) {
							canc = true;
							killing = false;
						}
					}
					if (canc) {
						Log.d(TAG, "cancelling kill");
						mHandler.removeCallbacks(killer);
					}
				}
			}
		}

		/*
		 * at this point, consume can only be true if this is an ACTION_DOWN.
		 * if we do want to consume this event, remember this fact so we can also consume
		 * the respective ACTION_UP event later.
		 */
		if (consume) {
			if (event.getKeyCode() == KEYCODE_VOLUME_DOWN)
				consumeNextVolDnUp = true;
			else
				consumeNextVolUpUp = true;
		} else if (event.getAction() == ACTION_UP) {
			/* if we consumed an ACTION_DOWN before, consume the ACTION_UP here. */
			if (event.getKeyCode() == KEYCODE_VOLUME_DOWN) {
				if (consumeNextVolDnUp) {
					consumeNextVolDnUp = false;
					consume = true;
				}
			} else {
				if (consumeNextVolUpUp) {
					consumeNextVolUpUp = false;
					consume = true;
				}
			}
		}

		return consume;
	}

}

/* TODO
 * - find keyboard-using app by inspecting its parent instead of blacklisting?
 * - turn button sequence into a proper state machine to allow for
 *   configurability
 * - basic activity to configure delays?
 */

/*
 * Copyright (c) 2020, Timo Buhrmester
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the srs bsns enterprises corp. nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

