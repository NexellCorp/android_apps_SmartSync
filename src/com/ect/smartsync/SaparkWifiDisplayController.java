package com.ect.smartsync;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.StringTokenizer;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;
import android.view.SurfaceHolder;

import com.example.android.wifidirect.SinkPlayer;

/**
 * Manages all of the various asynchronous interactions with the {@link WifiP2pManager}
 * on behalf of {@link WifiDisplayAdapter}.
 * <p>
 * This code is isolated from {@link WifiDisplayAdapter} so that we can avoid
 * accidentally introducing any deadlocks due to the display manager calling
 * outside of itself while holding its lock.  It's also way easier to write this
 * asynchronous code if we can assume that it is single-threaded.
 * </p><p>
 * The controller must be instantiated on the handler thread.
 * </p>
 */
public class SaparkWifiDisplayController {
    private static final String TAG = "WifiDisplayController";
    private static final boolean DEBUG = true;

    private static final int DEFAULT_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT = 50;
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;
//    private static final int RTSP_TIMEOUT_SECONDS = 30;
//    private static final int RTSP_TIMEOUT_SECONDS_CERT_MODE = 120;

    // We repeatedly issue calls to discover peers every so often for a few reasons.
    // 1. The initial request may fail and need to retried.
    // 2. Discovery will self-abort after any group is initiated, which may not necessarily
    //    be what we want to have happen.
    // 3. Discovery will self-timeout after 2 minutes, whereas we want discovery to
    //    be occur for as long as a client is requesting it be.
    // 4. We don't seem to get updated results for displays we've already found until
    //    we ask to discover again, particularly for the isSessionAvailable() property.
    private static final int DISCOVER_PEERS_INTERVAL_MILLIS = 10000;

//    private static final int CONNECT_MAX_RETRIES = 3;
    private static final int CONNECT_RETRY_DELAY_MILLIS = 500;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;

    private final WifiP2pManager mWifiP2pManager;
    private final Channel mWifiP2pChannel;

    private boolean mWifiP2pEnabled;
    private boolean mWfdEnabled;
    private boolean mWfdEnabling;
    private NetworkInfo mNetworkInfo;

    private final ArrayList<WifiP2pDevice> mAvailableWifiDisplayPeers =
            new ArrayList<WifiP2pDevice>();

    // True if Wifi display is enabled by the user.
    private boolean mWifiDisplayOnSetting;

    // True if a scan was requested independent of whether one is actually in progress.
    private boolean mScanRequested;

    // True if there is a call to discoverPeers in progress.
    private boolean mDiscoverPeersInProgress;

    // The device to which we want to connect, or null if we want to be disconnected.
    private WifiP2pDevice mDesiredDevice;

    // The device to which we are currently connecting, or null if we have already connected
    // or are not trying to connect.
    private WifiP2pDevice mConnectingDevice;

    // The device from which we are currently disconnecting.
    private WifiP2pDevice mDisconnectingDevice;

    // The device to which we were previously trying to connect and are now canceling.
    private WifiP2pDevice mCancelingDevice;

    // The device to which we are currently connected, which means we have an active P2P group.
    private WifiP2pDevice mConnectedDevice;

    // The group info obtained after connecting.
    private WifiP2pGroup mConnectedDeviceGroupInfo;

    // Number of connection retries remaining.
    private int mConnectionRetriesLeft;

//    // The remote display that is listening on the connection.
//    // Created after the Wifi P2P network is connected.
//    private RemoteDisplay mRemoteDisplay;
//
//    // The remote display interface.
//    private String mRemoteDisplayInterface;
//
//    // True if RTSP has connected.
//    private boolean mRemoteDisplayConnected;

    // The information we have most recently told WifiDisplayAdapter about.
//    private WifiDisplay mAdvertisedDisplay;
//    private Surface mAdvertisedDisplaySurface;
//    private int mAdvertisedDisplayWidth;
//    private int mAdvertisedDisplayHeight;
//    private int mAdvertisedDisplayFlags;

    // Certification
//    private boolean mWifiDisplayCertMode;
//    private int mWifiDisplayWpsConfig = WpsInfo.INVALID;

    private WifiP2pDevice mThisDevice;
    
    private final int MAX_SINK_REQ = 5;
    
    private boolean isConnected = false;
    private boolean isSinkPlaying = false;

    public SaparkWifiDisplayController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;

        mWifiP2pManager = (WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(context, handler.getLooper(), null);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        context.registerReceiver(mWifiP2pReceiver, intentFilter, null, mHandler);

        ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateSettings();
            }
        };

        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_DISPLAY_ON), false, settingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON), false, settingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_DISPLAY_WPS_CONFIG), false, settingsObserver);
        updateSettings();
    }
    
    public void end(){
    	mContext.unregisterReceiver(mWifiP2pReceiver);
    }
    
    public boolean isDisconnected() {
    	return mDesiredDevice == null && mConnectedDevice == null;
    }

    private void updateSettings() {
//        final ContentResolver resolver = mContext.getContentResolver();
        
        mWifiDisplayOnSetting = true;
//        mWifiDisplayCertMode = true;
        
//        Slog.d(TAG, "updateSettings() - resolver:" + Settings.Global.getInt(resolver, Settings.Global.WIFI_DISPLAY_ON, 0));
//        mWifiDisplayOnSetting = Settings.Global.getInt(resolver,
//                Settings.Global.WIFI_DISPLAY_ON, 0) != 0;
//        Slog.d(TAG, "updateSettings() - mWifiDisplayOnSetting:" + mWifiDisplayOnSetting);
//        mWifiDisplayCertMode = Settings.Global.getInt(resolver,
//                Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON, 0) != 0;

//        mWifiDisplayWpsConfig = WpsInfo.INVALID;
//        if (mWifiDisplayCertMode) {
//            mWifiDisplayWpsConfig = Settings.Global.getInt(resolver,
//                  Settings.Global.WIFI_DISPLAY_WPS_CONFIG, WpsInfo.INVALID);
//        }

        updateWfdEnableState();
    }

//    @Override
    public void dump(PrintWriter pw) {
    	Slog.d(TAG,"========================================================================");
        Slog.d(TAG,"mWifiDisplayOnSetting=" + mWifiDisplayOnSetting);
        Slog.d(TAG,"mWifiP2pEnabled=" + mWifiP2pEnabled);
        Slog.d(TAG,"mWfdEnabled=" + mWfdEnabled);
        Slog.d(TAG,"mWfdEnabling=" + mWfdEnabling);
        Slog.d(TAG,"mNetworkInfo=" + mNetworkInfo);
        Slog.d(TAG,"mScanRequested=" + mScanRequested);
        Slog.d(TAG,"mDiscoverPeersInProgress=" + mDiscoverPeersInProgress);
        Slog.d(TAG,"mDesiredDevice=" + describeWifiP2pDevice(mDesiredDevice));
        Slog.d(TAG,"mConnectingDisplay=" + describeWifiP2pDevice(mConnectingDevice));
        Slog.d(TAG,"mDisconnectingDisplay=" + describeWifiP2pDevice(mDisconnectingDevice));
        Slog.d(TAG,"mCancelingDisplay=" + describeWifiP2pDevice(mCancelingDevice));
        Slog.d(TAG,"mConnectedDevice=" + describeWifiP2pDevice(mConnectedDevice));
        Slog.d(TAG,"mConnectionRetriesLeft=" + mConnectionRetriesLeft);
//        Slog.d(TAG,"mRemoteDisplay=" + mRemoteDisplay);
//        Slog.d(TAG,"mRemoteDisplayInterface=" + mRemoteDisplayInterface);
//        Slog.d(TAG,"mRemoteDisplayConnected=" + mRemoteDisplayConnected);
//        Slog.d(TAG,"mAdvertisedDisplay=" + mAdvertisedDisplay);
//        Slog.d(TAG,"mAdvertisedDisplaySurface=" + mAdvertisedDisplaySurface);
//        Slog.d(TAG,"mAdvertisedDisplayWidth=" + mAdvertisedDisplayWidth);
//        Slog.d(TAG,"mAdvertisedDisplayHeight=" + mAdvertisedDisplayHeight);
//        Slog.d(TAG,"mAdvertisedDisplayFlags=" + mAdvertisedDisplayFlags);

        Slog.d(TAG,"mAvailableWifiDisplayPeers: size=" + mAvailableWifiDisplayPeers.size());
        for (WifiP2pDevice device : mAvailableWifiDisplayPeers) {
            Slog.d(TAG,"  " + describeWifiP2pDevice(device));
        }
        Slog.d(TAG,"========================================================================");
        
//        pw.println("mWifiDisplayOnSetting=" + mWifiDisplayOnSetting);
//        pw.println("mWifiP2pEnabled=" + mWifiP2pEnabled);
//        pw.println("mWfdEnabled=" + mWfdEnabled);
//        pw.println("mWfdEnabling=" + mWfdEnabling);
//        pw.println("mNetworkInfo=" + mNetworkInfo);
//        pw.println("mScanRequested=" + mScanRequested);
//        pw.println("mDiscoverPeersInProgress=" + mDiscoverPeersInProgress);
//        pw.println("mDesiredDevice=" + describeWifiP2pDevice(mDesiredDevice));
//        pw.println("mConnectingDisplay=" + describeWifiP2pDevice(mConnectingDevice));
//        pw.println("mDisconnectingDisplay=" + describeWifiP2pDevice(mDisconnectingDevice));
//        pw.println("mCancelingDisplay=" + describeWifiP2pDevice(mCancelingDevice));
//        pw.println("mConnectedDevice=" + describeWifiP2pDevice(mConnectedDevice));
//        pw.println("mConnectionRetriesLeft=" + mConnectionRetriesLeft);
//        pw.println("mRemoteDisplay=" + mRemoteDisplay);
//        pw.println("mRemoteDisplayInterface=" + mRemoteDisplayInterface);
//        pw.println("mRemoteDisplayConnected=" + mRemoteDisplayConnected);
//        pw.println("mAdvertisedDisplay=" + mAdvertisedDisplay);
//        pw.println("mAdvertisedDisplaySurface=" + mAdvertisedDisplaySurface);
//        pw.println("mAdvertisedDisplayWidth=" + mAdvertisedDisplayWidth);
//        pw.println("mAdvertisedDisplayHeight=" + mAdvertisedDisplayHeight);
//        pw.println("mAdvertisedDisplayFlags=" + mAdvertisedDisplayFlags);
//
//        pw.println("mAvailableWifiDisplayPeers: size=" + mAvailableWifiDisplayPeers.size());
//        for (WifiP2pDevice device : mAvailableWifiDisplayPeers) {
//            pw.println("  " + describeWifiP2pDevice(device));
//        }
    }

    public void requestStartScan() {
        if (!mScanRequested) {
            mScanRequested = true;
            updateScanState();
        }
    }

    public void requestStopScan() {
        if (mScanRequested) {
            mScanRequested = false;
            updateScanState();
        }
    }

//    public void requestConnect(String address) {
//        for (WifiP2pDevice device : mAvailableWifiDisplayPeers) {
//            if (device.deviceAddress.equals(address)) {
//                connect(device);
//            }
//        }
//    }

//    public void requestPause() {
//        if (mRemoteDisplay != null) {
//            mRemoteDisplay.pause();
//        }
//    }
//
//    public void requestResume() {
//        if (mRemoteDisplay != null) {
//            mRemoteDisplay.resume();
//        }
//    }

    public void requestDisconnect() {
        disconnect();
        
        removeGroup();
        
//        mWifiP2pManager.removeGroup(mWifiP2pChannel, new ActionListener() {
//            @Override
//            public void onSuccess() {
//                Slog.i(TAG, "requestDisconnect() - must remove group - success");
//            }
//
//            @Override
//            public void onFailure(int reason) {
//            	Slog.e(TAG, "requestDisconnect() - must remove group - failure " + reason);
//            }
//        });
    }

    private void updateWfdEnableState() {
        if (mWifiDisplayOnSetting && mWifiP2pEnabled) {
            // WFD should be enabled.
            if (!mWfdEnabled && !mWfdEnabling) {
                mWfdEnabling = true;

                WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
                wfdInfo.setWfdEnabled(true);
//                wfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);
                wfdInfo.setCoupledSinkSupportAtSink(false);
                wfdInfo.setCoupledSinkSupportAtSource(false);
                wfdInfo.setDeviceType(WifiP2pWfdInfo.PRIMARY_SINK);
                wfdInfo.setSessionAvailable(true);
                wfdInfo.setControlPort(DEFAULT_CONTROL_PORT);
                wfdInfo.setMaxThroughput(MAX_THROUGHPUT);
                mWifiP2pManager.setWFDInfo(mWifiP2pChannel, wfdInfo, new ActionListener() {
                    @Override
                    public void onSuccess() {
                        if (DEBUG) {
                            Slog.d(TAG, "Successfully set WFD info.");
                        }
                        if (mWfdEnabling) {
                            mWfdEnabling = false;
                            mWfdEnabled = true;
                            reportFeatureState();
                            updateScanState();
                        }
                    }

                    @Override
                    public void onFailure(int reason) {
                        if (DEBUG) {
                            Slog.d(TAG, "Failed to set WFD info with reason " + reason + ".");
                        }
                        mWfdEnabling = false;
                    }
                });
            }
        } else {
            // WFD should be disabled.
            if (mWfdEnabled || mWfdEnabling) {
                WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
                wfdInfo.setWfdEnabled(false);
                mWifiP2pManager.setWFDInfo(mWifiP2pChannel, wfdInfo, new ActionListener() {
                    @Override
                    public void onSuccess() {
                        if (DEBUG) {
                            Slog.d(TAG, "Successfully set WFD info.");
                        }
                    }

                    @Override
                    public void onFailure(int reason) {
                        if (DEBUG) {
                            Slog.d(TAG, "Failed to set WFD info with reason " + reason + ".");
                        }
                    }
                });
            }
            mWfdEnabling = false;
            mWfdEnabled = false;
            reportFeatureState();
            updateScanState();
            disconnect();
        }
    }

    private void reportFeatureState() {
        final int featureState = computeFeatureState();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onFeatureStateChanged(featureState);
            }
        });
    }

    private int computeFeatureState() {
        if (!mWifiP2pEnabled) {
            return WifiDisplayStatus.FEATURE_STATE_DISABLED;
        }
        return mWifiDisplayOnSetting ? WifiDisplayStatus.FEATURE_STATE_ON :
                WifiDisplayStatus.FEATURE_STATE_OFF;
    }

    private void updateScanState() {
        if (mScanRequested && mWfdEnabled && mDesiredDevice == null) {
            if (!mDiscoverPeersInProgress) {
                Slog.i(TAG, "Starting Wifi display scan.");
                mDiscoverPeersInProgress = true;
                handleScanStarted();
                tryDiscoverPeers();
            }
        } else {
            if (mDiscoverPeersInProgress) {
                // Cancel automatic retry right away.
                mHandler.removeCallbacks(mDiscoverPeers);

                // Defer actually stopping discovery if we have a connection attempt in progress.
                // The wifi display connection attempt often fails if we are not in discovery
                // mode.  So we allow discovery to continue until we give up trying to connect.
                if (mDesiredDevice == null || mDesiredDevice == mConnectedDevice) {
                    Slog.i(TAG, "Stopping Wifi display scan.");
                    mDiscoverPeersInProgress = false;
                    stopPeerDiscovery();
                    handleScanFinished();
                }
            }
        }
    }

    private void tryDiscoverPeers() {
        mWifiP2pManager.discoverPeers(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                if (DEBUG) {
                    Slog.d(TAG, "=====> Discover peers succeeded.  Requesting peers now.(removeRequestPeer)");
                }

                if (mDiscoverPeersInProgress) {
                    //requestPeers();
                }
            }

            @Override
            public void onFailure(int reason) {
                if (DEBUG) {
                    Slog.d(TAG, "Discover peers failed with reason " + reason + ".");
                }

                // Ignore the error.
                // We will retry automatically in a little bit.
            }
        });

        // Retry discover peers periodically until stopped.
        
        if(mDiscoverPeersInProgress){
        	Slog.d(TAG, "=====> retry discoverpeer");
        	mHandler.postDelayed(mDiscoverPeers, DISCOVER_PEERS_INTERVAL_MILLIS);
        	return;
        }
        Slog.d(TAG, "=====> stop retry discoverpeer");
    }

    private void stopPeerDiscovery() {
        mWifiP2pManager.stopPeerDiscovery(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                if (DEBUG) {
                    Slog.d(TAG, "Stop peer discovery succeeded. sapark setting");
                }
//                mDiscoverPeersInProgress = false;
            }

            @Override
            public void onFailure(int reason) {
                if (DEBUG) {
                    Slog.d(TAG, "Stop peer discovery failed with reason " + reason + ".");
                }
            }
        });
    }

    private void requestPeers() {
        mWifiP2pManager.requestPeers(mWifiP2pChannel, new PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peers) {
                if (DEBUG) {
//                    Slog.d(TAG, "################# >Received list of peers. \t" + peers);
//                    Slog.d(TAG, "################# >Received list : lastState:" + mLastThisDeviceStatus + ",empty:" + peers.getDeviceList().isEmpty());
                }
                
//                if(mLastThisDeviceStatus == WifiP2pDevice.CONNECTED && peers.getDeviceList().isEmpty()){
//                	Slog.d(TAG, "################# Peer CHANGED LOST" + peers);
//                	handlePeerDisconnected();
//                	return;
//                }

                mAvailableWifiDisplayPeers.clear();
                for (WifiP2pDevice device : peers.getDeviceList()) {
                    if (DEBUG) {
                        Slog.d(TAG, "++++++++" + describeWifiP2pDevice(device));
                    }
                	
                    if (isWifiDisplay(device)) {                   	
                        mAvailableWifiDisplayPeers.add(device);
                    }
                }

                if (mDiscoverPeersInProgress) {
                    handleScanResults();
                }
            }
        });
    }

    private void handleScanStarted() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onScanStarted();
            }
        });
    }

    private void handleScanResults() {
        final int count = mAvailableWifiDisplayPeers.size();
        final WifiDisplay[] displays = WifiDisplay.CREATOR.newArray(count);
        for (int i = 0; i < count; i++) {
            WifiP2pDevice device = mAvailableWifiDisplayPeers.get(i);
            displays[i] = createWifiDisplay(device);
            updateDesiredDevice(device);
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onScanResults(displays);
            }
        });
    }

    private void handleScanFinished() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onScanFinished();
            }
        });
    }
    
    private void handlePeerConnecting() {
    	mListener.onPeerConnecting();
    	
//    	mHandler.post(new Runnable() {
//			
//			@Override
//			public void run() {
//				mListener.onPeerConnecting(connecting);
//			}
//		});
    }
    
    private void handlePeerOpening(){
    	mHandler.post(new Runnable() {
			@Override
			public void run() {
				mListener.onPeerOpening();
			}
		});
    }
    
    private void handlePeerConnectingTimeout(){
    	mHandler.post(new Runnable() {
			@Override
			public void run() {
				mListener.onPeerConnectingTimeout();
			}
		});
    }
    
    private void handlePeerConnected(){
    	isConnected = true;
    	mHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(1500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				mListener.onPeerConnected(mConnectedDevice);
			}
		});
    }
    
    private void handlePeerDisconnecting(){
    	if (!isConnected){
    		Slog.v(TAG, "##################### handlePeerDisconnecting() - already disconnected");
    		return;
    	}
    	
    	mHandler.post(new Runnable() {
			@Override
			public void run() {
				Slog.v(TAG, "##################### handlePeerDisconnecting()");
//				disconnect();
				mListener.onPeerDisconnecting();
			}
		});
    }
    
    private void handlePeerDisconnected(){
//    	Slog.v(TAG, "##################### handlePeerDisconnected() - call");
    	isConnected = false;
    	mHandler.post(new Runnable() {
			@Override
			public void run() {
				Slog.v(TAG, "##################### handlePeerDisconnected()");
				disconnect();
				mListener.onPeerDisconnected(mConnectedDevice);
			}
		});
    }
    
    private void handlePeerConnectionFailure(){
//    	Slog.v(TAG, "##################### handlePeerDisconnected() - call");
    	isConnected = false;
    	mHandler.post(new Runnable() {
			@Override
			public void run() {
				Slog.v(TAG, "##################### handlePeerConnectionFailure()");
				//disconnect();
				mListener.onPeerConnectionFailure();
			}
		});
    }

    private void updateDesiredDevice(WifiP2pDevice device) {
        // Handle the case where the device to which we are connecting or connected
        // may have been renamed or reported different properties in the latest scan.
        final String address = device.deviceAddress;
        if (mDesiredDevice != null && mDesiredDevice.deviceAddress.equals(address)) {
            if (DEBUG) {
                Slog.d(TAG, "updateDesiredDevice: new information " + describeWifiP2pDevice(device));
            }
            mDesiredDevice.update(device);
//            if (mAdvertisedDisplay != null
//                    && mAdvertisedDisplay.getDeviceAddress().equals(address)) {
//                readvertiseDisplay(createWifiDisplay(mDesiredDevice));
//            }
        }
    }

//    private void connect(final WifiP2pDevice device) {
//        if (mDesiredDevice != null
//                && !mDesiredDevice.deviceAddress.equals(device.deviceAddress)) {
//            if (DEBUG) {
//                Slog.d(TAG, "connect: nothing to do, already connecting to " + describeWifiP2pDevice(device));
//            }
//            return;
//        }
//
//        if (mConnectedDevice != null
//                && !mConnectedDevice.deviceAddress.equals(device.deviceAddress)
//                && mDesiredDevice == null) {
//            if (DEBUG) {
//                Slog.d(TAG, "connect: nothing to do, already connected to "
//                        + describeWifiP2pDevice(device) + " and not part way through "
//                        + "connecting to a different device.");
//            }
//            return;
//        }
//
//        if (!mWfdEnabled) {
//            Slog.i(TAG, "Ignoring request to connect to Wifi display because the "
//                    +" feature is currently disabled: " + device.deviceName);
//            return;
//        }
//
//        mDesiredDevice = device;
//        mConnectionRetriesLeft = CONNECT_MAX_RETRIES;
//        updateConnection();
//    }
    
    private void removeGroup() {
		mWifiP2pManager.removeGroup(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                Slog.i(TAG, "removeGroup() - remove group - success");
            }

            @Override
            public void onFailure(int reason) {
            	Slog.e(TAG, "removeGroup() - remove group - failure " + reason);
            }
        });
    }

    private void disconnect() {
    	
    	if (isSinkPlaying) {
    		stopSink();
    	}
    	
        mDesiredDevice = null;
        updateConnection();
    }

    private void retryConnection() {
        // Cheap hack.  Make a new instance of the device object so that we
        // can distinguish it from the previous connection attempt.
        // This will cause us to tear everything down before we try again.
        mDesiredDevice = new WifiP2pDevice(mDesiredDevice);
        updateConnection();
    }

    /**
     * This function is called repeatedly after each asynchronous operation
     * until all preconditions for the connection have been satisfied and the
     * connection is established (or not).
     */
    private SinkPlayer sinkPlayer;
    private SurfaceHolder sf;
    private String sinkip = null;
    
    public void setSurface(SurfaceHolder holder){
    	sf = holder;
    }
	private void setSinkParameters(boolean start) {
		if (start) {
			writeSysfs("/sys/class/vfm/map", "rm default");
			writeSysfs("/sys/class/vfm/map", "add default decoder amvideo");
		} else {
			writeSysfs("/sys/class/vfm/map", "rm default");
			writeSysfs("/sys/class/vfm/map",
					"add default decoder ppmgr amvideo");
		}
	}
	private static int writeSysfs(String path, String val) {
		if (!new File(path).exists()) {
			return 1;
		}
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter(path), 64);
			try {
				writer.write(val);
			} finally {
				writer.close();
			}
			return 0;

		} catch (IOException e) {
			return 1;
		}
	}
    
    private void updateConnection() {
        // Step 0. Stop scans if necessary to prevent interference while connected.
        // Resume scans later when no longer attempting to connect.
    	Slog.d(TAG, "========================> STEP0");
        updateScanState();

        // Step 1. Before we try to connect to a new device, tell the system we
        // have disconnected from the old one.
        if (mConnectedDevice != mDesiredDevice) { // mRemoteDisplay != null &&
        	Slog.d(TAG, "========================> STEP1");
        	
//            Slog.i(TAG, "Stopped listening for RTSP connection on " + mRemoteDisplayInterface
//                    + " from Wifi display: " + mConnectedDevice.deviceName);

//            mRemoteDisplay.dispose();
//            mRemoteDisplay = null;
//            mRemoteDisplayInterface = null;
//            mRemoteDisplayConnected = false;
//            mHandler.removeCallbacks(mRtspTimeout);

            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_DISABLED);
//            unadvertiseDisplay();
            // continue to next step
        }

        // Step 2. Before we try to connect to a new device, disconnect from the old one.
        if (mDisconnectingDevice != null) {
        	Slog.d(TAG, "========================> STEP2");
            return; // wait for asynchronous callback
        }
        if (mConnectedDevice != null && mConnectedDevice != mDesiredDevice) {
        	Slog.d(TAG, "========================> STEP2-1");
            Slog.i(TAG, "Disconnecting from Wifi display: " + mConnectedDevice.deviceName);
            mDisconnectingDevice = mConnectedDevice;
            mConnectedDevice = null;
            mConnectedDeviceGroupInfo = null;

//            unadvertiseDisplay();

            final WifiP2pDevice oldDevice = mDisconnectingDevice;
            mWifiP2pManager.removeGroup(mWifiP2pChannel, new ActionListener() {
                @Override
                public void onSuccess() {
                    Slog.i(TAG, "Disconnected from Wifi display: " + oldDevice.deviceName);
                    next();
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to disconnect from Wifi display: "
                            + oldDevice.deviceName + ", reason=" + reason);
                    next();
                }

                private void next() {
                    if (mDisconnectingDevice == oldDevice) {
                        mDisconnectingDevice = null;
                        updateConnection();
                    }
                }
            });
            
            return; // wait for asynchronous callback
        }

        // Step 3. Before we try to connect to a new device, stop trying to connect
        // to the old one.
        if (mCancelingDevice != null) {
        	Slog.d(TAG, "========================> STEP3");
            return; // wait for asynchronous callback
        }
        if (mConnectingDevice != null && mConnectingDevice != mDesiredDevice) {
        	Slog.d(TAG, "========================> STEP3-1");
            Slog.i(TAG, "Canceling connection to Wifi display: " + mConnectingDevice.deviceName);
            mCancelingDevice = mConnectingDevice;
            mConnectingDevice = null;

//            unadvertiseDisplay();
            mHandler.removeCallbacks(mConnectionTimeout);

            final WifiP2pDevice oldDevice = mCancelingDevice;
            mWifiP2pManager.cancelConnect(mWifiP2pChannel, new ActionListener() {
                @Override
                public void onSuccess() {
                    Slog.i(TAG, "Canceled connection to Wifi display: " + oldDevice.deviceName);
                    next();
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to cancel connection to Wifi display: "
                            + oldDevice.deviceName + ", reason=" + reason);
                    next();
                }

                private void next() {
                    if (mCancelingDevice == oldDevice) {
                        mCancelingDevice = null;
                        updateConnection();
                    }
                }
            });
            return; // wait for asynchronous callback
        }

        // Step 4. If we wanted to disconnect, or we're updating after starting an
        // autonomous GO, then mission accomplished.
        if (mDesiredDevice == null) {
        	Slog.d(TAG, "========================> STEP4");
//            if (mWifiDisplayCertMode) {
//                mListener.onDisplaySessionInfo(getSessionInfo(mConnectedDeviceGroupInfo, 0));
//            }
//            unadvertiseDisplay();
            return; // done
        }

        // Step 5. Try to connect.
        if (mConnectedDevice == null && mConnectingDevice == null) {
        	Slog.d(TAG, "========================> STEP5");
        	// by sapark for source
//            Slog.i(TAG, "Connecting to Wifi display: " + mDesiredDevice.deviceName);
//
//            mConnectingDevice = mDesiredDevice;
//            WifiP2pConfig config = new WifiP2pConfig();
//            WpsInfo wps = new WpsInfo();
//            if (mWifiDisplayWpsConfig != WpsInfo.INVALID) {
//                wps.setup = mWifiDisplayWpsConfig;
//            } else if (mConnectingDevice.wpsPbcSupported()) {
//                wps.setup = WpsInfo.PBC;
//            } else if (mConnectingDevice.wpsDisplaySupported()) {
//                // We do keypad if peer does display
//                wps.setup = WpsInfo.KEYPAD;
//            } else {
//                wps.setup = WpsInfo.DISPLAY;
//            }
//            config.wps = wps;
//            config.deviceAddress = mConnectingDevice.deviceAddress;
//            // Helps with STA & P2P concurrency
//            config.groupOwnerIntent = WifiP2pConfig.MIN_GROUP_OWNER_INTENT;
//
//            WifiDisplay display = createWifiDisplay(mConnectingDevice);
//            advertiseDisplay(display, null, 0, 0, 0);
//
//            final WifiP2pDevice newDevice = mDesiredDevice;
//            mWifiP2pManager.connect(mWifiP2pChannel, config, new ActionListener() {
//                @Override
//                public void onSuccess() {
//                    // The connection may not yet be established.  We still need to wait
//                    // for WIFI_P2P_CONNECTION_CHANGED_ACTION.  However, we might never
//                    // get that broadcast, so we register a timeout.
//                    Slog.i(TAG, "Initiated connection to Wifi display: " + newDevice.deviceName);
//
//                    mHandler.postDelayed(mConnectionTimeout, CONNECTION_TIMEOUT_SECONDS * 1000);
//                }
//
//                @Override
//                public void onFailure(int reason) {
//                    if (mConnectingDevice == newDevice) {
//                        Slog.i(TAG, "Failed to initiate connection to Wifi display: "
//                                + newDevice.deviceName + ", reason=" + reason);
//                        mConnectingDevice = null;
//                        handleConnectionFailure(false);
//                    }
//                }
//            });
//            return; // wait for asynchronous callback
        }

        // Step 6. Listen for incoming RTSP connection.
        if (mConnectedDevice != null) { // && mRemoteDisplay == null
        	Slog.d(TAG, "========================> STEP6");
        	
            // for sinkplayer
            if(sf==null){
            	Slog.i(TAG, "Failed to not ready surfaceview ");
            	handleConnectionFailure(false);
            	return;
            }

            Slog.i(TAG, "=======> start sink play ");
            startSink();
            
        }
    }
    
   private void startSink(){
//	   startSink(false);
	   startSink(0);
   }
   

   private void startSink(final int restartIdx){

		Slog.v(TAG, "################## startSink() - idx:" + restartIdx);
		if (restartIdx >= MAX_SINK_REQ) {
			handlePeerConnectingTimeout();
			return;
		}

		if (mConnectedDevice == null) {
			Slog.v(TAG, "################## startSink() - disconnected :" + restartIdx);
			return;
		}

		BufferedReader reader;
		sinkip = null;
		try {

//			if (restartIdx == 0) {
				reader = new BufferedReader(new FileReader(new File(
						"/data/misc/dhcp/dnsmasq.leases")));
				String line;

				Slog.v(TAG, "################## startSink() - dnsmasq");

				while ((line = reader.readLine()) != null) {
					Slog.v(TAG, "====>dnsmasq: " + line);
					StringTokenizer st = new StringTokenizer(line);
//					String id = st.nextToken();
					st.nextToken();
					String mac = st.nextToken();
					String ip = st.nextToken();

					String temp1 = mac.substring(0, 11);
					String temp2 = mConnectedDevice.deviceAddress.substring(0,
							11);

					Slog.v(TAG, "====> dnsmsq, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
					if (temp1.compareToIgnoreCase(temp2) == 0) {
						sinkip = ip;
						break;
					}
				}
//			} else {
//				reader = new BufferedReader(new FileReader(new File( "/proc/net/arp")));
//				String l;
//				boolean first = true;
//				while ((l = reader.readLine()) != null) {
//					if (first) {
//						first = false;
//						continue;
//					}
//					Slog.v(TAG, "======> arp : " + l);
//					StringTokenizer st = new StringTokenizer(l);
//					String ip = st.nextToken();
//					String hw = st.nextToken();
//					String ty = st.nextToken();
//					String mac = st.nextToken();
//
//					String temp1 = mac.substring(0, 11);
//					String temp2 = mConnectedDevice.deviceAddress.substring(0, 11);
//
//					Slog.v(TAG, "====> arp, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
//					if (temp1.compareToIgnoreCase(temp2) == 0) {
//						sinkip = ip;
//						break;
//					}
//				}
//			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {

			Slog.v(TAG, "################## startSink() - gowner:" + mConnectedDeviceGroupInfo.isGroupOwner());
			if (!mConnectedDeviceGroupInfo.isGroupOwner()) {
				sinkip = "192.168.49.1";
			}

			if (sinkip != null || ((sinkip == null) && (restartIdx == 0))) {
				
				if(sinkip != null) {
					handlePeerConnected();
				}
				
				new Thread() {
					@Override
					public void run() {
						int port = mConnectedDevice.wfdInfo.getControlPort(); // getPortNumber(mConnectedDevice);
						Slog.v(TAG, "SinkPlay() ip:" + sinkip + ",port:" + port);
						
						String ip = sinkip;
						
//						isConnecting = true;
						isSinkPlaying = true;

						sinkPlayer = new SinkPlayer();
						sinkPlayer.setHolder(sf);
						sinkPlayer.setHostAndPort(ip, port);
						setSinkParameters(true);
						sinkPlayer.startRtsp();
						
						if (ip == null) {
							Slog.e(TAG, "################## startSink() ====> connectio trying");
							return;
						}
						
						isSinkPlaying = false;
//						handlePeerDisconnected();
						handlePeerDisconnecting();
//						isConnecting = false;
						Slog.v(TAG, "################## startSink() ====> close");
					}
				}.start();
			}

			if (sinkip == null && (restartIdx < MAX_SINK_REQ)) {
				new Thread() {
					@Override
					public void run() {
						try {
							Thread.sleep(2000);
							startSink(restartIdx + 1);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}.start();
			}

			// handlePeerConnected();
		}
   }
   
   public void stopSink() {
		sinkPlayer.stopRtsp();
   }
   
//   private void startSink(boolean restart){
//    	
//    	BufferedReader reader;
//    	sinkip = null;
//		try {
//			
//			if (restart) {
//				reader = new BufferedReader(new FileReader(new File("/proc/net/arp")));
//				String l;
//				boolean first = true;
//				while( (l = reader.readLine()) != null ) {
//					if (first){
//						first = false;
//						continue;
//					}
//					Slog.v(TAG, "======> arp : " + l);
//					StringTokenizer st = new StringTokenizer(l);
//			        String ip = st.nextToken(); 
//			        String hw = st.nextToken(); 
//			        String ty = st.nextToken();
//			        String mac = st.nextToken();
//			        
//			        String temp1 = mac.substring(0, 11);
//					String temp2 = mConnectedDevice.deviceAddress.substring(0, 11);
//					
//					Slog.v(TAG, "====> dnsmsq, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
//			        if (temp1.compareToIgnoreCase(temp2) == 0) {
//			        	sinkip = ip;
//			        	break;
//			        }
//			        
//			        Slog.v(TAG, "======> arp : ip:" + ip + ",mac:" + mac);
//				}
//			}else{
//				reader = new BufferedReader(new FileReader(new File("/data/misc/dhcp/dnsmasq.leases")));
//				String line;
//				
//				Slog.v(TAG, "====>dnsmasq: read");
//				while ( (line = reader.readLine()) != null )
//				{
//					Slog.v(TAG, "====>dnsmasq: " + line);
//					StringTokenizer st = new StringTokenizer(line);
//			        String id = st.nextToken(); 
//			        String mac = st.nextToken(); 
//			        String ip = st.nextToken();
//			        
//			        String temp1 = mac.substring(0, 11);
//					String temp2 = mConnectedDevice.deviceAddress.substring(0, 11);
//					
//					Slog.v(TAG, "====> dnsmsq, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
//			        if (temp1.compareToIgnoreCase(temp2) == 0) {
//			        	sinkip = ip;
//			        	break;
//			        }
//			        
////			        Slog.v(TAG, "====> dnsmsq, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
////			        if( mac.equals(mConnectedDevice.deviceAddress) ){
////			        	break;
////			        }
//				}
//			}
//			
//			
////			reader = new BufferedReader(new FileReader(new File("/data/misc/dhcp/dnsmasq.leases")));
////			String line;
////			
////			Slog.v(TAG, "====>dnsmasq: read");
////			while ( (line = reader.readLine()) != null )
////			{
////				Slog.v(TAG, "====>dnsmasq: " + line);
////				StringTokenizer st = new StringTokenizer(line);
////		        String id = st.nextToken(); 
////		        String mac = st.nextToken(); 
////		        sinkip = st.nextToken();
////		        
////		        String temp1 = mac.substring(0, 11);
////				String temp2 = mConnectedDevice.deviceAddress.substring(0, 11);
////				
////				Slog.v(TAG, "====> dnsmsq, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
////		        if (temp1.compareToIgnoreCase(temp2) == 0) {
////		        	break;
////		        }
////		        
//////		        Slog.v(TAG, "====> dnsmsq, mac:" + mac + ",dev:" + mConnectedDevice.deviceAddress);
//////		        if( mac.equals(mConnectedDevice.deviceAddress) ){
//////		        	break;
//////		        }
////			}
//		} catch (FileNotFoundException e) {
//			e.printStackTrace();
//		} catch (IOException e) {
//			e.printStackTrace();
//		} finally {
//			Slog.v(TAG, "=====> what: \n" + mConnectedDeviceGroupInfo.isGroupOwner());
//			if(!mConnectedDeviceGroupInfo.isGroupOwner()){
//	            sinkip = "192.168.49.1";
//			}
//			
////			if (sinkip == null){
////				Slog.i(TAG, "Failed to get local interface address :" + sinkip);
////				handleConnectionFailure(false);
////				return;
////			}
//			
//			if(sinkip != null || restart){
//				handlePeerConnected();
//			}
//			
//			new Thread() {
//				@Override
//				public void run() {
//					int port = mConnectedDevice.wfdInfo.getControlPort(); //getPortNumber(mConnectedDevice);
//					Slog.v(TAG, "SinkPlay() ip:" + sinkip + ",port:" + port);
//					
//					sinkPlayer = new SinkPlayer();	
//					sinkPlayer.setHolder(sf);
//					sinkPlayer.setHostAndPort(sinkip, port);
//					setSinkParameters(true);
//					sinkPlayer.startRtsp();
//				}
//			}.start();
//			
//			
//			if (sinkip == null && !restart){
//				new Thread() {
//					@Override
//					public void run() {						
//						try {
//							Thread.sleep(5000);
//							startSink(true);
//						} catch (InterruptedException e) {
//							e.printStackTrace();
//						}
//					}
//				}.start();
//			}
//			
//			//handlePeerConnected();
//		}
//    }

//    private WifiDisplaySessionInfo getSessionInfo(WifiP2pGroup info, int session) {
//        if (info == null) {
//            return null;
//        }
//        Inet4Address addr = getInterfaceAddress(info);
//        WifiDisplaySessionInfo sessionInfo = new WifiDisplaySessionInfo(
//                !info.getOwner().deviceAddress.equals(mThisDevice.deviceAddress),
//                session,
//                info.getOwner().deviceAddress + " " + info.getNetworkName(),
//                info.getPassphrase(),
//                (addr != null) ? addr.getHostAddress() : "");
//        if (DEBUG) {
//            Slog.d(TAG, sessionInfo.toString());
//        }
//        return sessionInfo;
//    }

    private void handleStateChanged(boolean enabled) {
        mWifiP2pEnabled = enabled;
        updateWfdEnableState();
    }

    private void handlePeersChanged() {
        // Even if wfd is disabled, it is best to get the latest set of peers to
        // keep in sync with the p2p framework
        requestPeers();
    }

    private void handleConnectionChanged(NetworkInfo networkInfo) {
    	 if (DEBUG) {
             Slog.d(TAG, "handleConnectionChanged() - mWfdEnabled:" + mWfdEnabled);
             Slog.d(TAG, "handleConnectionChanged() - isConnected:" + networkInfo.isConnected());
             Slog.d(TAG, "handleConnectionChanged() - mDesiredDevice:" + mDesiredDevice);
//             Slog.d(TAG, "handleConnectionChanged() - mWifiDisplayCertMode:" + mWifiDisplayCertMode);
         }
    	 
        mNetworkInfo = networkInfo;
        if (mWfdEnabled && networkInfo.isConnected()) {
        	Slog.v(TAG, "describ" + networkInfo.describeContents());
//            if (mDesiredDevice != null) {  //  || mWifiDisplayCertMode
            	
            	if (DEBUG) {
            		Slog.d(TAG, "handleConnectionChanged() - request Group info" );
            	}
            	
                mWifiP2pManager.requestGroupInfo(mWifiP2pChannel, new GroupInfoListener() {
                    @Override
                    public void onGroupInfoAvailable(WifiP2pGroup info) {
                        if (DEBUG) {
                            Slog.d(TAG, "Received group info: " + describeWifiP2pGroup(info));
                        }

                        if (mConnectingDevice != null && !info.contains(mConnectingDevice)) {
                            Slog.i(TAG, "Aborting connection to Wifi display because "
                                    + "the current P2P group does not contain the device "
                                    + "we expected to find: " + mConnectingDevice.deviceName
                                    + ", group info was: " + describeWifiP2pGroup(info));
                            handleConnectionFailure(false);
                            return;
                        }

                        if (mDesiredDevice != null && !info.contains(mDesiredDevice)) {
                            disconnect();
                            return;
                        }

//                        if (mWifiDisplayCertMode) {
                        	                        	
                            boolean owner = info.getOwner().deviceAddress.equals(mThisDevice.deviceAddress);
                            
                            if(DEBUG){
                            	Slog.d(TAG, "handleConnectionChanged() - owneraddr : " + info.getOwner().deviceAddress + ",this:" + mThisDevice.deviceAddress);
                            	Slog.d(TAG, "handleConnectionChanged() - owner : \n" + owner);
                            	Slog.d(TAG, "handleConnectionChanged() - groupinfo : \n" + info);
                            	Slog.d(TAG, "handleConnectionChanged() - groupowner :" + info.isGroupOwner());
                            	Slog.d(TAG, "handleConnectionChanged() - child : \n" + info.getClientList());
                            }
                            
                            if (owner && info.getClientList().isEmpty()) {
                                // this is the case when we started Autonomous GO,
                                // and no client has connected, save group info
                                // and updateConnection()
                                mConnectingDevice = mDesiredDevice = null;
                                mConnectedDeviceGroupInfo = info;
                                updateConnection();
                            } else if (mConnectingDevice == null && mDesiredDevice == null) {
                                // this is the case when we received an incoming connection
                                // from the sink, update both mConnectingDevice and mDesiredDevice
                                // then proceed to updateConnection() below
                                mConnectingDevice = mDesiredDevice = owner ?
                                        info.getClientList().iterator().next() : info.getOwner();
                            }
//                        }
                        
                        // by sapark
//                        handlePeerConnecting(mConnectingDevice);

                        if (mConnectingDevice != null && mConnectingDevice == mDesiredDevice) {
                            Slog.i(TAG, "Connected to Wifi display: " + mConnectingDevice.deviceName);

                            mHandler.removeCallbacks(mConnectionTimeout);
                            mConnectedDeviceGroupInfo = info;
                            mConnectedDevice = mConnectingDevice;
                            mConnectingDevice = null;
                            
                            
                            if(DEBUG){
                            	Slog.d(TAG, "handleConnectionChanged() - connected : " + mConnectedDevice);
                            }
                            updateConnection();
                        }
                    }
                });
//            }
        } else {
            mConnectedDeviceGroupInfo = null;

            // Disconnect if we lost the network while connecting or connected to a display.
            if (mConnectingDevice != null || mConnectedDevice != null) {
                disconnect();
            }

            // After disconnection for a group, for some reason we have a tendency
            // to get a peer change notification with an empty list of peers.
            // Perform a fresh scan.
            if (mWfdEnabled) {
                requestPeers();
            }
        }
    }

    private final Runnable mDiscoverPeers = new Runnable() {
        @Override
        public void run() {
            tryDiscoverPeers();
        }
    };

    private final Runnable mConnectionTimeout = new Runnable() {
        @Override
        public void run() {
            if (mConnectingDevice != null && mConnectingDevice == mDesiredDevice) {
                Slog.i(TAG, "Timed out waiting for Wifi display connection after "
                        + CONNECTION_TIMEOUT_SECONDS + " seconds: "
                        + mConnectingDevice.deviceName);
                handleConnectionFailure(true);
            }
        }
    };
    
    private boolean mSourceConnectionTimeoutFlag = false;
    private final Runnable mSourceConnectionTimeout = new Runnable() {
    	@Override
    	public void run() {
    		if (!mSourceConnectionTimeoutFlag){
    			Slog.i(TAG, "mSourceConnectionTimeout() - timeout - false");
    			return;
    		}
    		Slog.i(TAG, "mSourceConnectionTimeout() - timeout - true");
    		removeGroup();
    		handlePeerConnectingTimeout();
    	}
    };

//    private final Runnable mRtspTimeout = new Runnable() {
//        @Override
//        public void run() {
//            if (mConnectedDevice != null
//                    && mRemoteDisplay != null && !mRemoteDisplayConnected) {
//                Slog.i(TAG, "Timed out waiting for Wifi display RTSP connection after "
//                        + RTSP_TIMEOUT_SECONDS + " seconds: "
//                        + mConnectedDevice.deviceName);
//                handleConnectionFailure(true);
//            }
//        }
//    };

    private void handleConnectionFailure(boolean timeoutOccurred) {
        Slog.i(TAG, "Wifi display connection failed!");

        if (mDesiredDevice != null) {
            if (mConnectionRetriesLeft > 0) {
                final WifiP2pDevice oldDevice = mDesiredDevice;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mDesiredDevice == oldDevice && mConnectionRetriesLeft > 0) {
                            mConnectionRetriesLeft -= 1;
                            Slog.i(TAG, "Retrying Wifi display connection.  Retries left: "
                                    + mConnectionRetriesLeft);
                            retryConnection();
                        }
                    }
                }, timeoutOccurred ? 0 : CONNECT_RETRY_DELAY_MILLIS);
            } else {
                disconnect();
            }
        }
    }

//    private void advertiseDisplay(final WifiDisplay display,
//            final Surface surface, final int width, final int height, final int flags) {
//        if (!Objects.equal(mAdvertisedDisplay, display)
//                || mAdvertisedDisplaySurface != surface
//                || mAdvertisedDisplayWidth != width
//                || mAdvertisedDisplayHeight != height
//                || mAdvertisedDisplayFlags != flags) {
//            final WifiDisplay oldDisplay = mAdvertisedDisplay;
//            final Surface oldSurface = mAdvertisedDisplaySurface;
//
//            mAdvertisedDisplay = display;
//            mAdvertisedDisplaySurface = surface;
//            mAdvertisedDisplayWidth = width;
//            mAdvertisedDisplayHeight = height;
//            mAdvertisedDisplayFlags = flags;
//
//            mHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    if (oldSurface != null && surface != oldSurface) {
//                        mListener.onDisplayDisconnected();
//                    } else if (oldDisplay != null && !oldDisplay.hasSameAddress(display)) {
//                        mListener.onDisplayConnectionFailed();
//                    }
//
//                    if (display != null) {
//                        if (!display.hasSameAddress(oldDisplay)) {
//                            mListener.onDisplayConnecting(display);
//                        } else if (!display.equals(oldDisplay)) {
//                            // The address is the same but some other property such as the
//                            // name must have changed.
//                            mListener.onDisplayChanged(display);
//                        }
//                        if (surface != null && surface != oldSurface) {
//                            mListener.onDisplayConnected(display, surface, width, height, flags);
//                        }
//                    }
//                }
//            });
//        }
//    }

//    private void unadvertiseDisplay() {
//        advertiseDisplay(null, null, 0, 0, 0);
//    }
//
//    private void readvertiseDisplay(WifiDisplay display) {
//        advertiseDisplay(display, mAdvertisedDisplaySurface,
//                mAdvertisedDisplayWidth, mAdvertisedDisplayHeight,
//                mAdvertisedDisplayFlags);
//    }

//    private static Inet4Address getInterfaceAddress(WifiP2pGroup info) {
//        NetworkInterface iface;
//        try {
//            iface = NetworkInterface.getByName(info.getInterface());
//        } catch (SocketException ex) {
//            Slog.w(TAG, "Could not obtain address of network interface "
//                    + info.getInterface(), ex);
//            return null;
//        }
//
//        Enumeration<InetAddress> addrs = iface.getInetAddresses();
//        while (addrs.hasMoreElements()) {
//            InetAddress addr = addrs.nextElement();
//            if (addr instanceof Inet4Address) {
//                return (Inet4Address)addr;
//            }
//        }
//
//        Slog.w(TAG, "Could not obtain address of network interface "
//                + info.getInterface() + " because it had no IPv4 addresses.");
//        return null;
//    }
//
//    private static int getPortNumber(WifiP2pDevice device) {
//        if (device.deviceName.startsWith("DIRECT-")
//                && device.deviceName.endsWith("Broadcom")) {
//            // These dongles ignore the port we broadcast in our WFD IE.
//            return 8554;
//        }
//        return DEFAULT_CONTROL_PORT;
//    }

    private static boolean isWifiDisplay(WifiP2pDevice device) {
        return device.wfdInfo != null
                && device.wfdInfo.isWfdEnabled()
                && isPrimarySinkDeviceType(device.wfdInfo.getDeviceType());
    }

    private static boolean isPrimarySinkDeviceType(int deviceType) {
        return deviceType == WifiP2pWfdInfo.PRIMARY_SINK
                || deviceType == WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK;
    }

    private static String describeWifiP2pDevice(WifiP2pDevice device) {
        return device != null ? device.toString().replace('\n', ',') : "null";
    }

    private static String describeWifiP2pGroup(WifiP2pGroup group) {
        return group != null ? group.toString().replace('\n', ',') : "null";
    }

    private static WifiDisplay createWifiDisplay(WifiP2pDevice device) {
        return new WifiDisplay(device.deviceAddress, device.deviceName, null,
                true, device.wfdInfo.isSessionAvailable(), false);
    }

//    private int mLastThisDeviceStatus = -1;
    
    private final BroadcastReceiver mWifiP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)) {
                // This broadcast is sticky so we'll always get the initial Wifi P2P state
                // on startup.
                boolean enabled = (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED)) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                if (DEBUG) {
                    Slog.d(TAG, "######## >Received WIFI_P2P_STATE_CHANGED_ACTION: enabled="
                            + enabled);
                }

                handleStateChanged(enabled);
            } else if (action.equals(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)) {
                if (DEBUG) {
                    Slog.d(TAG, "######## >Received WIFI_P2P_PEERS_CHANGED_ACTION.");
                }
                
                handlePeersChanged();
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                NetworkInfo networkInfo = (NetworkInfo)intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                if (DEBUG) {
                    Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: networkInfo=" + networkInfo);
                    Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: current:" + isConnected + ",network:" + networkInfo.isConnected());
                }
                
                if (mThisDevice != null && mThisDevice.status == WifiP2pDevice.CONNECTED && !networkInfo.isConnected()) {
                	Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: dev:connected, network:false");
                	mHandler.removeCallbacks(mSourceConnectionTimeout);
                	removeGroup();
                	handlePeerConnectionFailure();
                	return;
                }
                
//                Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: connection timeout remove");
//                mHandler.removeCallbacks(mSourceConnectionTimeout);
                
                if (!networkInfo.isConnected() && (networkInfo.getDetailedState().equals(NetworkInfo.DetailedState.FAILED))) {
                	handlePeerConnectionFailure();
                	Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: connection failure");
                	return;
                }
                
                if(isConnected && networkInfo.isConnected()) {
                	Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: aready connected");
                	return;
                }
                
                if (!isConnected && !networkInfo.isConnected()) {
                	Slog.d(TAG, "######## >Received WIFI_P2P_CONNECTION_CHANGED_ACTION: aready disconnected");
                	return;
                }
                
                if(networkInfo.isConnected()){
                	mSourceConnectionTimeoutFlag = false;
                	mHandler.removeCallbacks(mSourceConnectionTimeout);
                	handlePeerConnecting();
                } else {
                	handlePeerDisconnected();
                }

//                if (mLastThisDeviceStatus == WifiP2pDevice.CONNECTED && !networkInfo.isConnected()) {
//                	Slog.d(TAG, "######## Received WIFI_P2P_CONNECTION_CHANGED_ACTION: disconnected" );
//                	handlePeerDisconnected();
//                }
                handleConnectionChanged(networkInfo);
            } else if (action.equals(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)) {
                mThisDevice = (WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (DEBUG) {
                    Slog.d(TAG, "######## >Received WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: mThisDevice= " + mThisDevice);
                }
                
                if (mThisDevice.status == WifiP2pDevice.CONNECTED) {
                	Slog.d(TAG, "######## >Received WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: timeout ");
                	mHandler.postDelayed(mSourceConnectionTimeout, 20 * 1000);
                	mSourceConnectionTimeoutFlag = true;
                	handlePeerOpening();
//                	handlePeerConnecting();
                }
                
//                if(mLastThisDeviceStatus == WifiP2pDevice.CONNECTED && mThisDevice.status != WifiP2pDevice.CONNECTED) {
//                	handlePeerDisconnected();
//                }
//                
//                if(mLastThisDeviceStatus != WifiP2pDevice.CONNECTED && mThisDevice.status == WifiP2pDevice.CONNECTED ){
//                	handlePeerConnecting();
//                }
//                mLastThisDeviceStatus = mThisDevice.status;
                
            }
        }
    };
    

    /**
     * Called on the handler thread when displays are connected or disconnected.
     */
    public interface Listener {
        void onFeatureStateChanged(int featureState);

        void onScanStarted();
        void onScanResults(WifiDisplay[] availableDisplays);
        void onScanFinished();

//        void onDisplayConnecting(WifiDisplay display);
//        void onDisplayConnectionFailed();
//        void onDisplayChanged(WifiDisplay display);
//        void onDisplayConnected(WifiDisplay display,
//                Surface surface, int width, int height, int flags);
//        void onDisplaySessionInfo(WifiDisplaySessionInfo sessionInfo);
//        void onDisplayDisconnected();
        
        /* peer request connect */
        void onPeerOpening();
        void onPeerConnecting();
        void onPeerConnected(WifiP2pDevice peer);
        void onPeerDisconnecting();
        void onPeerDisconnected(WifiP2pDevice peer);
        void onPeerConnectingTimeout();
        void onPeerConnectionFailure();
        
    }
}