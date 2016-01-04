/*
 * CalWatch
 * Copyright (C) 2014 by Dan Wallach
 * Home page: http://www.cs.rice.edu/~dwallach/calwatch/
 * Licensing: http://www.cs.rice.edu/~dwallach/calwatch/licensing.html
 */
package org.dwallach.calwatch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import fr.nicolaspomepuy.androidwearcrashreport.wear.CrashReporter;

/**
 * This class pairs up with WearSender
 * Created by dwallach on 8/25/14.
 *
 */
public class WearReceiverService extends WearableListenerService implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    private final static String TAG = "WearReceiverService";

    // private List<EventWrapper> eventList = null;
    // private int maxLevel = 0;
    // private int faceMode = ClockFace.FACE_TOOL;
    private GoogleApiClient googleApiClient = null;
    private static WearReceiverService singleton;

    public WearReceiverService() {
        super();
        Log.v(TAG, "starting listening service");
        singleton = this;
    }

    public static WearReceiverService getSingleton() { return singleton; }

    private void newEventBytes(byte[] eventBytes) {
        Log.v(TAG, "newEventBytes: " + eventBytes.length);
        ClockState clockState = ClockState.getSingleton();
        if(clockState == null) {
            Log.e(TAG, "whoa, no ClockState yet?!");
            return;
        }

        clockState.setProtobuf(eventBytes);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "service starting!");

        // why is this necessary?
        initGoogle();

        // Nicholas Pomepuy's crash reporting library claims to be able to pass things
        // going kaboom all the way out to the Play Store for us. Let's see if it works.
        CrashReporter.getInstance(this).start();

        // this also seems a reasonable place to set up the battery monitor

        BatteryWrapper.init(this);

        // and to load any saved data while we're waiting on the phone to give us fresh data

        ClockState clockState = ClockState.getSingleton();
        if(clockState == null) {
            Log.e(TAG, "whoa, no ClockState yet?!");
        } else {
            if(clockState.getWireInitialized()) {
                Log.v(TAG, "clock state already initialized, no need to go to saved prefs");
            } else {
                loadPreferences();
            }
        }

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "data changed");
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                DataItem item = event.getDataItem();

                Log.d(TAG, "--> item found: " + item.toString());
                if (item.getUri().getPath().compareTo(Constants.SettingsPath) == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    byte[] eventbuf = dataMap.getByteArray(Constants.DataKey);
                    Log.d(TAG, "----> it's an event for us, nbytes: " + eventbuf.length);

                    // the first time through, this seems to be null; weird, but at
                    // least it's easy to ignore the nullness
                    if(eventbuf != null) {
                        newEventBytes(eventbuf);
                        savePreferences(eventbuf); // save this for subsequent restarts
                    }
                }
            }
        }
    }

    private void initGoogle() {
        if(googleApiClient == null) {
            Log.v(TAG, "Trying to connect to GoogleApi");
            googleApiClient = new GoogleApiClient.Builder(this).
                    addApi(Wearable.API).
                    addConnectionCallbacks(this).
                    addOnConnectionFailedListener(this).
                    build();
            googleApiClient.connect();
        }
    }

        @Override
    public void onCreate() {
        super.onCreate();

        Log.v(TAG, "onCreate!");
        initGoogle();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        // Apparently unrelated to connections with the phone.
        Log.v(TAG, "Connected to Google Api Service");
    }


    @Override
    public void onConnectionSuspended(int cause) {
        // The connection has been interrupted.
        // Disable any UI components that depend on Google APIs
        // until onConnected() is called.

        // Apparently unrelated to connections with the phone.
        Log.v(TAG, "suspended connection!");
        if(googleApiClient != null && googleApiClient.isConnected())
            googleApiClient.disconnect();
        googleApiClient = null;
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        // This callback is important for handling errors that
        // may occur while attempting to connect with Google.

        // Apparently unrelated to connections with the phone.

        Log.v(TAG, "lost connection!");
        if(googleApiClient != null && googleApiClient.isConnected())
            googleApiClient.disconnect();
        googleApiClient = null;
    }

    public void onPeerConnected(Node peer) {
        Log.v(TAG, "phone is connected!, "+peer.getDisplayName());
    }

    public void onPeerDisconnected(Node peer) {
        Log.v(TAG, "phone is disconnected!, " + peer.getDisplayName());
    }

    /**
     * Take a serialized state update and commit it to stable storage.
     * @param eventBytes protobuf-serialed WireUpdate (typically received from the phone)
     */
    public void savePreferences(byte[] eventBytes) {
        // if there's not enough state there to be real, then don't save it
        if(eventBytes == null || eventBytes.length < 1)
            return;

        Log.v(TAG, "savePreferences: " + eventBytes.length + " bytes");
        SharedPreferences prefs = getSharedPreferences(Constants.PrefsKey, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // TODO there's no reason to save state like this any more; maybe we could merge with mobile/PreferencesHelper

        // Kinda sad that we need to base64-encode our state before we save it, but the SharedPreferences
        // interface doesn't allow for arbitrary arrays of bytes. So yeah, base64-encoded,
        // protobuf-encoded, WireUpdate structure, which itself has a handful of ints and a
        // variable-length array of WireEvents, which are themselves just a bunch of long's.
        editor.putString("savedState", Base64.encodeToString(eventBytes, Base64.DEFAULT));

        if(!editor.commit())
            Log.v(TAG, "savePreferences commit failed ?!");
    }

    /**
     * load saved state, if it's present, and use it to initialize the watchface.
     */
    public void loadPreferences() {
        Log.v(TAG, "loadPreferences");

        SharedPreferences prefs = getSharedPreferences(Constants.PrefsKey, MODE_PRIVATE);
        String savedState = prefs.getString("savedState", "");

        if(savedState.length() > 0) {
            try {
                byte[] eventBytes = Base64.decode(savedState, Base64.DEFAULT);
                newEventBytes(eventBytes);

            } catch (IllegalArgumentException e) {
                Log.e(TAG, "failed to decode base64 saved state: " + e.toString());
            }
        }
    }


    public static void kickStart(Context context) {
        Log.v(TAG, "kickStart");
        // start the calendar service, if it's not already running
        if(getSingleton() == null) {
            Log.v(TAG, "launching WearReceiverService via intent");
            Intent serviceIntent = new Intent(context, WearReceiverService.class);
            context.startService(serviceIntent);
        }
    }
}
