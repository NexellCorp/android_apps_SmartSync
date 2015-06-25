/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.ect.smartsync;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.os.Bundle;
import android.os.FileObserver;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import com.ect.smartsync.DeviceListFragment.DeviceActionListener;
import com.example.android.wifidirect.SinkPlayer;

/**
 * An activity that uses WiFi Direct APIs to discover and connect with available
 * devices. WiFi Direct APIs are asynchronous and rely on callback mechanism
 * using interfaces to notify the application of operation success or failure.
 * The application should also register a BroadcastReceiver for notification of
 * WiFi state related events.
 */
public class WiFiDirectActivity extends Activity implements ChannelListener,
		DeviceActionListener {

	public static final String TAG = "wifidirectdemo";
	private WifiP2pManager manager;
	private boolean isWifiP2pEnabled = false;
	private boolean retryChannel = false;

	private final IntentFilter intentFilter = new IntentFilter();
	private Channel channel;
	private BroadcastReceiver receiver = null;

	public static final String DNSMASQ_IP_ADDR_ACTION = "android.net.dnsmasq.IP_ADDR";
	private final static String FB0_BLANK = "/sys/class/graphics/fb0/blank";
	public static final String DNSMASQ_MAC_EXTRA = "MAC_EXTRA";
	public static final String DNSMASQ_IP_EXTRA = "IP_EXTRA";
	public static final String DNSMASQ_PORT_EXTRA = "PORT_EXTRA";

	private FileObserver mAddrObserver;
	private SinkPlayer sinkPlayer;

	private String ip;
	private String temp_port;
	private int port;
	
	private SurfaceView mSurfaceView;
	private SurfaceHolder mSurfaceHolder;
    private boolean mSurfaceHolderReady;
    
    private Activity me;
    
	
	/**
	 * @param isWifiP2pEnabled
	 *            the isWifiP2pEnabled to set
	 */
	public void setIsWifiP2pEnabled(boolean isWifiP2pEnabled) {
		this.isWifiP2pEnabled = isWifiP2pEnabled;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		me = this;

		//test
		mSurfaceHolderReady = false;
		mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView2);
		mSurfaceHolder = mSurfaceView.getHolder();  
		mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {
            
            @Override  
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i("MainActivity", "surfaceDestroyed");
                mSurfaceHolderReady = false;
            }  
              
            @Override  
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i("MainActivity", "surfaceCreated");
                mSurfaceHolderReady = true;
            }
              
            @Override  
            public void surfaceChanged(SurfaceHolder holder, int format, int width,  
                    int height) {  
                Log.i("MainActivity", "surfaceChanged");
                mSurfaceHolderReady = true;
            }  
        }); 
		
		// add necessary intent values to be matched.
		wifiStatus();

		intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
		intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
		intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
		intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
		intentFilter.addAction(WiFiDirectActivity.DNSMASQ_IP_ADDR_ACTION);

		manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
		channel = manager.initialize(this, getMainLooper(), null);
		
       
	}
	
    	
	private void wifiStatus()
	{
		WifiManager wifimanager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		if(wifimanager != null)
		{
			if(!wifimanager.isWifiEnabled())
			{
				Toast.makeText(this, "Please open wifi(p2p)!", Toast.LENGTH_LONG).show();
			}
		}
		
	}

	public void startSink(String host, String port2) {

		ip = host;
		temp_port = port2;

		try {
			port = Integer.parseInt(temp_port);

		} catch (NumberFormatException e) {
			e.printStackTrace();
		}

		new Thread() {
			@Override
			public void run() {
				// TODO Auto-generated method stub
				sinkPlayer = new SinkPlayer();	
				sinkPlayer.setHolder(mSurfaceHolder);
				sinkPlayer.setHostAndPort(ip, port);
				setSinkParameters(true);
				closeOsd();
				sinkPlayer.startRtsp();
			}
		}.start();

	}

	@Override
	protected void onStop() {
		// TODO Auto-generated method stub
		Log.v("SAPARK", "============================================= stop");
		closeDiscover();		
		
		Log.v("SAPARK", ">>> cancelConnect() ");
		manager.cancelConnect(channel, new ActionListener() {
			
			@Override
			public void onSuccess() {
				Log.v("SAPARK", ">>> cancelConnect() onSuccess");
			}
			
			@Override
			public void onFailure(int arg0) {
				Log.v("SAPARK", ">>> cancelConnect() onFailure");
			}
		});
		Log.v("SAPARK", ">>> clearLocalServices() ");
		manager.clearLocalServices(channel, new ActionListener() {
			
			@Override
			public void onSuccess() {
				Log.v("SAPARK", ">>> clearLocalServices() onSuccess");
			}
			
			@Override
			public void onFailure(int arg0) {
				Log.v("SAPARK", ">>> clearLocalServices() onFailure");
			}
		});
		
		
		setSinkParameters(false);
		super.onStop();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_VOLUME_UP:
		case KeyEvent.KEYCODE_VOLUME_DOWN:
		case KeyEvent.KEYCODE_VOLUME_MUTE:
			openOsd();
			break;

		case KeyEvent.KEYCODE_BACK:
			Log.v("SAPARK", "============================================= back");
			openOsd();
			
			new AlertDialog.Builder(this)
			.setTitle(R.string.app_name)
			.setMessage(R.string.exit_yn)
			.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface arg0, int arg1) {
					Log.v("SAPARK", "11++++++++++++++++++++++++exit this App");
					me.finish();
				}
			})
			.setNegativeButton(R.string.no,	new DialogInterface.OnClickListener() {
				
				@Override
				public void onClick(DialogInterface arg0, int arg1) {
					Log.v("SAPARK", "11++++++++++++++++++++++++exit this cancel");
				}
			}).show();
			
			break;
		}
		return super.onKeyDown(keyCode, event);
	}

	private static void switchGraphicLayer(boolean open) {
		// Log.d(TAG, (open?"open":"close") + " graphic layer");
		writeSysfs(FB0_BLANK, open ? "0" : "1");
	}

	public static void openOsd() {
		switchGraphicLayer(true);

	}

	public static void closeOsd() {
		switchGraphicLayer(false);
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

	/** register the BroadcastReceiver with the intent values to be matched */
	@Override
	public void onResume() {
		super.onResume();
		receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
		registerReceiver(receiver, intentFilter);
		
		//startDiscover();
	}

	@Override
	public void onPause() {
		super.onPause();
		unregisterReceiver(receiver);
	}

	/**
	 * Remove all peers and clear all fields. This is called on
	 * BroadcastReceiver receiving a state change event.
	 */
	public void resetData() {
		DeviceListFragment fragmentList = (DeviceListFragment) getFragmentManager()
				.findFragmentById(R.id.frag_list);
		DeviceDetailFragment fragmentDetails = (DeviceDetailFragment) getFragmentManager()
				.findFragmentById(R.id.frag_detail);
		if (fragmentList != null) {
			fragmentList.clearPeers();
		}
		if (fragmentDetails != null) {
			fragmentDetails.resetViews();
		}
	}
	
	protected void startDiscover(){
		Log.v("SAPARK", "============================================= startDiscover(" + isWifiP2pEnabled + ")");
		mSurfaceView.setVisibility(View.INVISIBLE);
		if (!isWifiP2pEnabled) {
			Toast.makeText(WiFiDirectActivity.this,
					R.string.p2p_off_warning, Toast.LENGTH_SHORT).show();
			return;
		}
		final DeviceListFragment fragment = (DeviceListFragment) getFragmentManager()
				.findFragmentById(R.id.frag_list);
		fragment.onInitiateDiscovery();
		
		// discoverPeers 
		manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {

			// ?�순???�레?�워?�로 ?�청???�공?�다??콜백
			// WIFI_P2P_PEERS_CHANGED_ACTION 브로?�캐?�트�??�신?�면 requestPeer() ?�청
			@Override
			public void onSuccess() {
				Toast.makeText(WiFiDirectActivity.this,
						"Discovery Initiated", Toast.LENGTH_SHORT).show();
			}
			// ?�순???�레?�워?�로 ?�청 ?�패 콜백
			@Override
			public void onFailure(int reasonCode) {
				Toast.makeText(WiFiDirectActivity.this,
						"Discovery Failed : " + reasonCode,
						Toast.LENGTH_SHORT).show();
				
				// ?�시 ?�스커버?�작
				startDiscover();				
			}
		});
	}
	
	protected void closeDiscover(){
		
		manager.stopPeerDiscovery(channel, null);
		
		mSurfaceView.setVisibility(View.VISIBLE);
		final DeviceListFragment fragment = (DeviceListFragment) getFragmentManager()
				.findFragmentById(R.id.frag_list);
		fragment.closeProgress();
		
		if (!isWifiP2pEnabled) {
			Toast.makeText(WiFiDirectActivity.this,
					R.string.p2p_off_warning, Toast.LENGTH_SHORT).show();
			return;
		}
	}
	
//	protected void cancelDiscover(){
//		DeviceDetailFragment fragment = (DeviceDetailFragment) getFragmentManager()
//				.findFragmentById(R.id.frag_detail);
//	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.action_items, menu);
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.atn_direct_enable:
			if (manager != null && channel != null) {

				// Since this is the system wireless settings activity, it's
				// not going to send us a result. We will be notified by
				// WiFiDeviceBroadcastReceiver instead.

				startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
			} else {
				Log.e(TAG, "channel or manager is null");
			}
			return true;

		case R.id.atn_direct_discover:
			if (!isWifiP2pEnabled) {
				Toast.makeText(WiFiDirectActivity.this,
						R.string.p2p_off_warning, Toast.LENGTH_SHORT).show();
				return true;
			}
			final DeviceListFragment fragment = (DeviceListFragment) getFragmentManager()
					.findFragmentById(R.id.frag_list);
			fragment.onInitiateDiscovery();
			
			// discoverPeers 
			manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {

				// ?�순???�레?�워?�로 ?�청???�공?�다??콜백
				// WIFI_P2P_PEERS_CHANGED_ACTION 브로?�캐?�트�??�신?�면 requestPeer() ?�청
				@Override
				public void onSuccess() {
					Toast.makeText(WiFiDirectActivity.this,
							"Discovery Initiated", Toast.LENGTH_SHORT).show();
				}
				// ?�순???�레?�워?�로 ?�청 ?�패 콜백
				@Override
				public void onFailure(int reasonCode) {
					Toast.makeText(WiFiDirectActivity.this,
							"Discovery Failed : " + reasonCode,
							Toast.LENGTH_SHORT).show();
				}
			});
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void showDetails(WifiP2pDevice device) {
		DeviceDetailFragment fragment = (DeviceDetailFragment) getFragmentManager()
				.findFragmentById(R.id.frag_detail);
		fragment.showDetails(device);

	}

	@Override
	public void connect(WifiP2pConfig config) {

		manager.connect(channel, config, new ActionListener() {

			@Override
			public void onSuccess() {
				// WiFiDirectBroadcastReceiver will notify us. Ignore for now.
			}

			@Override
			public void onFailure(int reason) {
				Toast.makeText(WiFiDirectActivity.this,
						"Connect failed. Retry.", Toast.LENGTH_SHORT).show();
			}
		});
	}

	@Override
	public void disconnect() {
		final DeviceDetailFragment fragment = (DeviceDetailFragment) getFragmentManager()
				.findFragmentById(R.id.frag_detail);
		fragment.resetViews();
		manager.removeGroup(channel, new ActionListener() {

			@Override
			public void onFailure(int reasonCode) {
				Log.d(TAG, "Disconnect failed. Reason :" + reasonCode);

			}

			@Override
			public void onSuccess() {
				fragment.getView().setVisibility(View.GONE);
			}

		});
	}

	@Override
	public void onChannelDisconnected() {
		// we will try once more
		if (manager != null && !retryChannel) {
			Toast.makeText(this, "Channel lost. Trying again",
					Toast.LENGTH_LONG).show();
			resetData();
			retryChannel = true;
			manager.initialize(this, getMainLooper(), this);
		} else {
			Toast.makeText(
					this,
					"Severe! Channel is probably lost premanently. Try Disable/Re-Enable P2P.",
					Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public void cancelDisconnect() {

		/*
		 * A cancel abort request by user. Disconnect i.e. removeGroup if
		 * already connected. Else, request WifiP2pManager to abort the ongoing
		 * request
		 */
		if (manager != null) {
			final DeviceListFragment fragment = (DeviceListFragment) getFragmentManager()
					.findFragmentById(R.id.frag_list);
			if (fragment.getDevice() == null
					|| fragment.getDevice().status == WifiP2pDevice.CONNECTED) {
				disconnect();
			} else if (fragment.getDevice().status == WifiP2pDevice.AVAILABLE
					|| fragment.getDevice().status == WifiP2pDevice.INVITED) {

				manager.cancelConnect(channel, new ActionListener() {

					@Override
					public void onSuccess() {
						Toast.makeText(WiFiDirectActivity.this,
								"Aborting connection", Toast.LENGTH_SHORT)
								.show();
					}

					@Override
					public void onFailure(int reasonCode) {
						Toast.makeText(
								WiFiDirectActivity.this,
								"Connect abort request failed. Reason Code: "
										+ reasonCode, Toast.LENGTH_SHORT)
								.show();
					}
				});
			}
		}

	}
}
