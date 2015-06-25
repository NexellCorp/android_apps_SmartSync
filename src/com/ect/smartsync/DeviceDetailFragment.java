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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Fragment;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * A fragment that manages a particular peer and allows interaction with device
 * i.e. setting up network connection and transferring data.
 */
public class DeviceDetailFragment extends Fragment implements
		ConnectionInfoListener {

	protected static final int CHOOSE_FILE_RESULT_CODE = 20;
	private View mContentView = null;
	private WifiP2pDevice device;
	private WifiP2pInfo info;
	ProgressDialog progressDialog = null;

	private Context mcontext;
	private final String FB0_BLANK = "/sys/class/graphics/fb0/blank";
	private final String ipFilePath = "/data/misc/dhcp/dnsmasq.leases";
	private Handler handler_ip;
	
	String peer_ip;
	

	public static String peer_macaddr;
	public static String peer_name;
	

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		
		mContentView = inflater.inflate(R.layout.device_detail, null);
		mcontext = getActivity();
		handler_ip = new Handler() {

			@Override
			public void handleMessage(Message msg) {
				// TODO Auto-generated method stub

				new Thread() {

					@Override
					public void run() {
						// TODO Auto-generated method stub		
						
						
						parseDnsmasqAddr(ipFilePath);
					}
				}.start();

			}

		};

		return mContentView;
	}

	@Override
	public void onConnectionInfoAvailable(final WifiP2pInfo info) {
		if (progressDialog != null && progressDialog.isShowing()) {
			progressDialog.dismiss();
		}
		this.info = info;
		this.getView().setVisibility(View.VISIBLE);

		// The owner IP is now known.
		TextView view = (TextView) mContentView.findViewById(R.id.group_owner);
		view.setText(getResources().getString(R.string.group_owner_text)
				+ ((info.isGroupOwner == true) ? getResources().getString(
						R.string.yes) : getResources().getString(R.string.no)));

		// InetAddress from WifiP2pInfo struct.
		view = (TextView) mContentView.findViewById(R.id.device_info);
		view.setText("Group Owner IP - "
				+ info.groupOwnerAddress.getHostAddress() + " isGroup - " + info.isGroupOwner);
		

		Log.v("DSYOU",  "Group Owner IP - "
				+ info.groupOwnerAddress.getHostAddress() + " isGroup - " + info.isGroupOwner);

		peer_ip = null;
		if(info.isGroupOwner != true)
		{		
			view.setText("Group Owner IP - "
					+ info.groupOwnerAddress.getHostAddress() + " isGroup - " + info.isGroupOwner
					+ " " + info.groupOwnerAddress.getHostAddress()) ;	
			

			Log.v("DSYOU",  "Group Owner IP - "
					+ info.groupOwnerAddress.getHostAddress() + " isGroup - " + info.isGroupOwner
					+ " " + info.groupOwnerAddress.getHostAddress()) ;	
			
			peer_ip = info.groupOwnerAddress.getHostAddress();
		}
		
		
//		831         int type = wfd.getDeviceType(); 
//		832         mP2pControlPort = wfd.getControlPort(); 
//		833 
//		 
//		834         boolean source = (type == WifiP2pWfdInfo.WFD_SOURCE) || (type == WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK); 
//		835         addLog("isWifiDisplaySource() type["+type+"] is-source["+source+"] port["+mP2pControlPort+"]"); 

		
			
		TimerTask task = new TimerTask() {
			public void run() {
				// execute the task
				Message msg = new Message();
				handler_ip.sendMessage(msg);
				progressDialog.dismiss();

			}
		};
		Timer timer = new Timer();
		timer.schedule(task, 5000);

		progressDialog = ProgressDialog.show(getActivity(),
				"Open Miracast Function", "Waiting Source data", true, true);

	}

	private void parseDnsmasqAddr(String fullname) {
		// TODO Auto-generated method stub
		File file = new File(fullname);
		BufferedReader reader = null;
		
		String id = new String();
		String mac = new String();
		String ip = new String();
		String port = new String();
		String name = new String();


		if( peer_ip == null)
		{
			try {
				reader = new BufferedReader(new FileReader(file));				
				String line;
				
				String devaddr  = new String(peer_macaddr);//접속한 디바이스의 MAC
				
				while ( (line = reader.readLine()) != null )
				{
					Log.d("DSYOU~~~~", line );
					
					StringTokenizer st = new StringTokenizer(line);
			        id = st.nextToken(); 
			        mac = st.nextToken(); 
			        ip = st.nextToken(); 
			        name = st.nextToken(); 	
			        
			        if(peer_name.contains( new String("Galaxy") ) == true)
			        {	
				        port = "7236"; 
			        }
			        else if(peer_name.contains( new String("G2") ) == true )
			        {
			        	port = "8554"; 
			        }
			        else if(peer_name.contains( new String("G3") ) == true)
			        {
			        	port = "8554"; 
			        	
			        }
			        else if(peer_name.contains( new String("G Pro") ) == true)
			        {
			        	port = "8554"; 
			        	
			        }
			        else if(peer_name.contains( new String("Vu") ) == true)
			        {
			        	port = "8554"; 
			        }
			        else if(peer_name.contains( new String("SHV") ) == true)
			        {
			        	port = "7236";       	
			        }
			        else
			        {
			        	port = "7236"; 
			        }
			        

					String temp1 = mac.substring(0, 11);
					String temp2 = devaddr.substring(0, 11);
					
			        if( temp1.compareToIgnoreCase(temp2) == 0 )
			        {			      
						Log.d("DSYOU~~~~", mac + " == " + devaddr );
			        	break;
			        }
			        else
			        {
						Log.d("DSYOU~~~~", mac + " != " + devaddr );	
			        }
				}
				
				//mac = reader.readLine();
				//ip = reader.readLine();
				Log.d("DSYOU", "MAC[" + mac + "] IP[" + ip + "] DEVNAME[" + name + "]");
				reader.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if (reader != null)
					try {
						reader.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
			}
		}
		else
		{
			ip  = peer_ip;			
			
			if(peer_name.contains( new String("Galaxy") ) == true)
	        {	
		        port = "7236"; 
	        }
	        else if(peer_name.contains( new String("G2") ) == true )
	        {
	        	port = "7236"; 
	        }
	        else if(peer_name.contains( new String("G3") ) == true)
	        {
	        	port = "8554"; 
	        }
	        else
	        {
	        	port = "7236"; 
	        }
		}

		Intent intent = new Intent(WiFiDirectActivity.DNSMASQ_IP_ADDR_ACTION);
		intent.putExtra(WiFiDirectActivity.DNSMASQ_IP_EXTRA, ip);
		//intent.putExtra(WiFiDirectActivity.DNSMASQ_IP_EXTRA, "192.168.49.1");
		intent.putExtra(WiFiDirectActivity.DNSMASQ_PORT_EXTRA, port);
		mcontext.sendBroadcast(intent);	

	}

	/**
	 * Updates the UI with device data
	 * 
	 * @param device
	 *            the device to be displayed
	 */
	public void showDetails(WifiP2pDevice device) {
		this.device = device;
		this.getView().setVisibility(View.VISIBLE);
		TextView view = (TextView) mContentView
				.findViewById(R.id.device_address);
		view.setText(device.deviceAddress);
		view = (TextView) mContentView.findViewById(R.id.device_info);
		view.setText(device.toString());
		
		Log.v("DSYOU",  device.toString() ) ;	

	}

	/**
	 * Clears the UI fields after a disconnect or direct mode disable operation.
	 */
	public void resetViews() {
		TextView view = (TextView) mContentView
				.findViewById(R.id.device_address);
		view.setText(R.string.empty);
		view = (TextView) mContentView.findViewById(R.id.device_info);
		view.setText(R.string.empty);
		view = (TextView) mContentView.findViewById(R.id.group_owner);
		view.setText(R.string.empty);
		view = (TextView) mContentView.findViewById(R.id.status_text);
		view.setText(R.string.empty);
	
		this.getView().setVisibility(View.GONE);
	}


}
