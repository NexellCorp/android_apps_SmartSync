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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.util.Log;

/**
 * A BroadcastReceiver that notifies of important wifi p2p events.
 */
public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {

	private WifiP2pManager manager;
	private Channel channel;
	private WiFiDirectActivity activity;
	
	private boolean isConnected = false;

	/**
	 * @param manager
	 *            WifiP2pManager system service
	 * @param channel
	 *            Wifi p2p channel
	 * @param activity
	 *            activity associated with the receiver
	 */
	public WiFiDirectBroadcastReceiver(WifiP2pManager manager, Channel channel,
			WiFiDirectActivity activity) {
		super();
		this.manager = manager;
		this.channel = channel;
		this.activity = activity;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context,
	 * android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
        //
		String action = intent.getAction();
		
		Log.v("SAPARK", "============================================= onReceive()" + action);
		
		if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

			// UI update to indicate wifi p2p status.
			int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
			if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
				// Wifi Direct mode is enabled
				isConnected = false;
				
				activity.setIsWifiP2pEnabled(true);
				Log.v("SAPARK", "============================================= WIFI_P2P_STATE_ENABLED(true)");
				activity.startDiscover();
				
			} else {
				activity.setIsWifiP2pEnabled(false);
				activity.resetData();
				Log.v("SAPARK", "============================================= WIFI_P2P_STATE_ENABLED(false)");
				activity.closeDiscover();
			}
			
			WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
			wfdInfo.setWfdEnabled(true);
			wfdInfo.setDeviceType(WifiP2pWfdInfo.PRIMARY_SINK);
			wfdInfo.setSessionAvailable(true);
			//wfdInfo.setControlPort(8554);
			wfdInfo.setControlPort(7236);	// sapark for test
			wfdInfo.setMaxThroughput(50);			


			manager.setWFDInfo(channel, wfdInfo, new ActionListener() {

				@Override
				public void onSuccess() {
					// TODO Auto-generated method stub
					Log.d("zzl:::", "wifi-sink" + "success");
				}

				@Override
				public void onFailure(int arg0) {
					Log.d("zzl:::", "wifi-sink" + "fail");
					// TODO Auto-generated method stub
				}
			});
			Log.d(WiFiDirectActivity.TAG, "P2P state changed - " + state);
			
		} else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {

			// request available peers from the wifi p2p manager. This is an
			// asynchronous call and the calling activity is notified with a
			// callback on PeerListListener.onPeersAvailable()
			
			if (manager != null) {
				manager.requestPeers(channel, (PeerListListener) activity
						.getFragmentManager().findFragmentById(R.id.frag_list));
			}
			Log.d(WiFiDirectActivity.TAG, "P2P peers changed");
			
		} else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION
				.equals(action)) {

			if (manager == null) {
				return;
			}

			NetworkInfo networkInfo = (NetworkInfo) intent
					.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
			
			Log.v("SAPARK", "============================================= WIFI_P2P_CONNECTION_CHANGED_ACTION(" + networkInfo.isConnected() + ")");

			if (networkInfo.isConnected()) {
				
				//// discover progress close;
				activity.closeDiscover();
				//onCancelDiscovery
				
				// sapark if aready connected return 
//				if( isConnected ){
//					Log.v("SAPARK", "============================================= WIFI_P2P_CONNECTION_CHANGED_ACTION()- aready connected");
//					return;
//				}

				isConnected = true;
//				WiFiDirectActivity.closeOsd();
				// we are connected with the other device, request connection
				// info to find group owner IP

				DeviceDetailFragment fragment = (DeviceDetailFragment) activity
						.getFragmentManager()
						.findFragmentById(R.id.frag_detail);
				manager.requestConnectionInfo(channel, fragment);
				
				// by sapark for gethering connection port
				manager.requestGroupInfo(channel, new GroupInfoListener() {
					
					@Override
					public void onGroupInfoAvailable(WifiP2pGroup info) {
						
						// how can find connection req device when descovered devices.
						if( info.getClientList() == null || info.getClientList().size() <= 0 ){
							Log.v("SAPARK", "====> what : " + info);
							return;
						}
						
						
						WifiP2pDevice device = (WifiP2pDevice) info.getClientList().toArray()[0];
						
						int port = 7236;
				        if (device.deviceName.startsWith("DIRECT-")
				                && device.deviceName.endsWith("Broadcom")) {
				            // These dongles ignore the port we broadcast in our WFD IE.
				        	port =  8554;
				        }

						Log.v("SAPARK", "===> info: " + port);
						Log.v("SAPARK", "===> port: " + device.wfdInfo.getControlPort());
						Log.v("SAPARK", "===> number: " + info.getClientList().toArray()[0]);
						Log.v("SAPARK", "===> info: groupowner:" + info.isGroupOwner());
						Log.v("SAPARK", "===> info: groupowner:" + info.getOwner().toString());
					}
				});
				
				//WiFiDirectActivity.closeOsd();

			} else {
				// It's a disconnect
				activity.resetData();
				
				// restart
				activity.startDiscover();
			}
		} else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
				.equals(action)) {
			DeviceListFragment fragment = (DeviceListFragment) activity
					.getFragmentManager().findFragmentById(R.id.frag_list);
			fragment.updateThisDevice((WifiP2pDevice) intent
					.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));

			WiFiDirectActivity.openOsd();
			
			//fragment.

		} else if (WiFiDirectActivity.DNSMASQ_IP_ADDR_ACTION.equals(action)) {
			
//			if(isConnected){
//				Log.v("SAPARK", "============================================= DNSMASQ_IP_ADDR_ACTION()- aready connected");
//				return;
//			}
			
			String ip = intent
					.getStringExtra(WiFiDirectActivity.DNSMASQ_IP_EXTRA);
			String port = intent
					.getStringExtra(WiFiDirectActivity.DNSMASQ_PORT_EXTRA);
			Log.d("zzl:::", " ^^^^^^^^^^^^^^ Got " + ip + "   " + port);

			activity.startSink(ip, port);
		}
	}
}
