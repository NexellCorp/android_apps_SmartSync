package com.ect.smartsync;

import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.media.RemoteDisplay;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.PersistentGroupInfoListener;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Handler;
import android.util.Log;
import android.util.Slog;
import android.view.Surface;

import com.ect.smartsync.SaparkWifiDisplayController.Listener;

public class WifiDisplaySinkController {

	
	private static final String TAG = WifiDisplaySinkController.class.getSimpleName();
	private static final boolean DEBUG = true;
	
    private static final int DEFAULT_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT = 50;
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;
    private static final int RTSP_TIMEOUT_SECONDS = 30;
    private static final int RTSP_TIMEOUT_SECONDS_CERT_MODE = 120;

    // We repeatedly issue calls to discover peers every so often for a few reasons.
    // 1. The initial request may fail and need to retried.
    // 2. Discovery will self-abort after any group is initiated, which may not necessarily
    //    be what we want to have happen.
    // 3. Discovery will self-timeout after 2 minutes, whereas we want discovery to
    //    be occur for as long as a client is requesting it be.
    // 4. We don't seem to get updated results for displays we've already found until
    //    we ask to discover again, particularly for the isSessionAvailable() property.
    private static final int DISCOVER_PEERS_INTERVAL_MILLIS = 10000;

    private static final int CONNECT_MAX_RETRIES = 3;
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

    // The remote display that is listening on the connection.
    // Created after the Wifi P2P network is connected.
    private RemoteDisplay mRemoteDisplay;

    // The remote display interface.
    private String mRemoteDisplayInterface;

    // True if RTSP has connected.
    private boolean mRemoteDisplayConnected;

    // The information we have most recently told WifiDisplayAdapter about.
    private WifiDisplay mAdvertisedDisplay;
    private Surface mAdvertisedDisplaySurface;
    private int mAdvertisedDisplayWidth;
    private int mAdvertisedDisplayHeight;
    private int mAdvertisedDisplayFlags;

    // True if Wifi display is enabled by the user.
    // miracast 니까 항상 display on 따라서제거
    // private boolean mWifiDisplayOnSetting;
    
    // Certification
    private boolean mWifiDisplayCertMode;
    private int mWifiDisplayWpsConfig = WpsInfo.INVALID;

    private WifiP2pDevice mThisDevice;

    public WifiDisplaySinkController(Context context, Handler handler, Listener listener) {
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
    }
    
    public void end(){
    	mContext.unregisterReceiver(mWifiP2pReceiver);
    }
    
    public void requestStartScan() {
    	
    	if (DEBUG) {
    		Slog.d(TAG, "====> requestStartScan() : requested:" + mScanRequested);
    	}
    	
        if (!mScanRequested) {
            mScanRequested = true;
            updateScanState();
        }
    }
    
    private void updateScanState() {
        if (mScanRequested && mWfdEnabled && mDesiredDevice == null) {
            if (!mDiscoverPeersInProgress) {
                Slog.i(TAG, "Starting Wifi display scan.");
                mDiscoverPeersInProgress = true;
                
                
                Slog.d(TAG, "=====> Discover peers");
                mWifiP2pManager.discoverPeers(mWifiP2pChannel, new ActionListener() {
                    @Override
                    public void onSuccess() {
                        if (DEBUG) {
                            Slog.d(TAG, "=====> Discover peers succeeded.  Requesting peers now.");
                        }

                        if (mDiscoverPeersInProgress) {
                           // requestPeers();
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
                
//                handleScanStarted();
//                tryDiscoverPeers();
            }
        } else {
            if (mDiscoverPeersInProgress) {
                // Cancel automatic retry right away.
//                mHandler.removeCallbacks(mDiscoverPeers);

                // Defer actually stopping discovery if we have a connection attempt in progress.
                // The wifi display connection attempt often fails if we are not in discovery
                // mode.  So we allow discovery to continue until we give up trying to connect.
                if (mDesiredDevice == null || mDesiredDevice == mConnectedDevice) {
                    Slog.i(TAG, "Stopping Wifi display scan.");
                    mDiscoverPeersInProgress = false;
//                    stopPeerDiscovery();
//                    handleScanFinished();
                }
            }
        }
    }
    
    private int computeFeatureState() {
        if (!mWifiP2pEnabled) {
            return WifiDisplayStatus.FEATURE_STATE_DISABLED;
        }
        return WifiDisplayStatus.FEATURE_STATE_ON;
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
    
    private void handleStateChanged(boolean enabled) {
        mWifiP2pEnabled = enabled;
        updateWfdEnableState();
    }
    
    private void updateWfdEnableState() {
        if (mWifiP2pEnabled) {
            // WFD should be enabled.
            if (!mWfdEnabled && !mWfdEnabling) {
                mWfdEnabling = true;

                WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
                wfdInfo.setWfdEnabled(true);
//                wfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);
                wfdInfo.setDeviceType(WifiP2pWfdInfo.PRIMARY_SINK);
                wfdInfo.setSessionAvailable(true);
                wfdInfo.setControlPort(DEFAULT_CONTROL_PORT);
                wfdInfo.setMaxThroughput(MAX_THROUGHPUT);
                 
               
                WifiP2pConfig config = new WifiP2pConfig();
                config.groupOwnerIntent = 0;
                
                if (DEBUG) {
                    Log.d(TAG, "=====> WFD enabled: PRIMARY_SINK");
                }
                
                mWifiP2pManager.setWFDInfo(mWifiP2pChannel, wfdInfo, new ActionListener() {
                    @Override
                    public void onSuccess() {
                        if (DEBUG) {
                            Log.d(TAG, "Successfully set WFD info.");
                        }
                        if (mWfdEnabling) {
                            mWfdEnabling = false;
                            mWfdEnabled = true;
                            
                            reportFeatureState();
//                            updateScanState();
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
                
                if (DEBUG) {
                    Log.d(TAG, "=====> WFD disabled: ");
                }
                
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
//            updateScanState();
//            disconnect();
        }
    }
    
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
                    Slog.d(TAG, "Received WIFI_P2P_STATE_CHANGED_ACTION: enabled="
                            + enabled);
                }

                handleStateChanged(enabled);
                
            } else if (action.equals(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)) {
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_PEERS_CHANGED_ACTION.");
                }

//                handlePeersChanged();
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                NetworkInfo networkInfo = (NetworkInfo)intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_CONNECTION_CHANGED_ACTION: networkInfo="
                            + networkInfo);
                }

//                handleConnectionChanged(networkInfo);
            } else if (action.equals(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)) {
                mThisDevice = (WifiP2pDevice) intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: \n mThisDevice :: "
                            + mThisDevice);
                }
            }
        }
    };
}
