package com.ect.smartsync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnKeyListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.ect.smartsync.SaparkWifiDisplayController.Listener;

public class SaparkWFDActivity extends Activity implements
		SaparkWifiDisplayController.Listener {

	private static final String TAG = SaparkWFDActivity.class.getSimpleName();
	private static final boolean DEBUG = false;
	
	// by sapark final -> remove
	private Context mContext;
	private Handler mHandler;

	private Listener mListener;
	
	private SaparkWifiDisplayController controller;
	
	private TextView txt;
//	private TextView txtState;
	
	private SurfaceView sfv;
	
	SmartSyncProgressDialog progressDialog = null;
	
	private boolean isStarted = true;
	
	private boolean closeAppAfterDisconnected = false;
	private boolean isCloseApp = false;
	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		Log.i(TAG, "####################### onCreate()");
		
		closeAppAfterDisconnected = false;
		
		setContentView(R.layout.saparkwfd);
		
		mContext = this;
		mHandler = new Handler();
		mListener = this;
		
		controller = new SaparkWifiDisplayController(mContext, mHandler, mListener);
		controller.setSurface(null);
		
		txt = (TextView)findViewById(R.id.txt);
		
		
		LogText("onCreate()");
		showProgress(getString(R.string.pending));
		
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}
	
	
	
	@Override
	protected void onResume() {
		super.onResume();
		
		isCloseApp = false;
		
		SurfaceHolder sh = sfv == null ? null : sfv.getHolder();
		Log.i(TAG, "####################### onResume() : " + controller + ":" + sh);
		
		startMSCMsgReceiver();
		isStarted = true;
		
		if(controller == null){
			controller = new SaparkWifiDisplayController(mContext, mHandler, mListener);
			controller.setSurface(sh);
		}
	}

	@Override
	protected void onStop(){
		super.onStop();
		
		Log.i(TAG, "####################### onStop()");
		isStarted = false;
		hidePorgress();
		
//		controller.requestDisconnect();
//		controller.requestStopScan();
//		controller.end();
//		controller = null;
	}

	@Override
	protected void onPause() {
		
		super.onPause();
		
		stopMSCMsgReceiver();
		
		isStarted = false;
		
		Log.i(TAG, "####################### onPause()");
		controller.requestDisconnect();
		controller.requestStopScan();
		controller.end();
		controller = null;
	}
	
	private void LogText(String msg){
		if(DEBUG && txt != null){
			txt.append(msg + "\n");
		}
	}
	
	
	private void closeApplication() {
		isCloseApp = true;
		controller.requestDisconnect();
	}
	
	
	private void createSurfaceView(){
		
		Log.i(TAG, "####################### createSurfaceView()");
		
		if( sfv != null ){
			deleteSurfaceView();
		}
		
		sfv = new SurfaceView(this);
		sfv.setLayoutParams(new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		sfv.getHolder().addCallback(new SurfaceHolder.Callback() {
			@Override
			public void surfaceDestroyed(SurfaceHolder holder) {
				Log.i("MainActivity", "surfaceDestroyed");
				if (controller != null)
					controller.setSurface(null);
			}

			@Override
			public void surfaceCreated(SurfaceHolder holder) {
				Log.i("MainActivity", "surfaceCreated");
				if (controller != null)
					controller.setSurface(holder);
			}

			@Override
			public void surfaceChanged(SurfaceHolder holder, int format,
					int width, int height) {
				Log.i("MainActivity", "surfaceChanged");
				if (controller != null)
					controller.setSurface(holder);
			}
		}); 
		ViewGroup vg = (ViewGroup)findViewById(R.id.losfv);
		vg.addView(sfv);
	}
	
	private void deleteSurfaceView(){
		Log.i(TAG, "####################### deleteSurfaceView()");
		
		ViewGroup vg = (ViewGroup)findViewById(R.id.losfv);
		vg.removeAllViews();
		sfv = null;
	}
	

	@Override
	public void onFeatureStateChanged(int featureState) {
		if(DEBUG){
			Log.d(TAG, "onFeatureStateChanged() - featureState:" + featureState);
			controller.dump(null);
		}
		
		if (featureState == WifiDisplayStatus.FEATURE_STATE_DISABLED) {
			progressDialog.setText(getString(R.string.disabled_wifi));
		}
		
		if( featureState > 2){
			controller.requestStartScan();
		}
	}

	@Override
	public void onScanStarted() {
		if(DEBUG){
			Log.d(TAG, "onScanStarted()");
			LogText("onScanStarted()");
		}
		
		
		Log.i(TAG, "####################### onScanStarted() = app close : " + isStarted);
		
		if(!isStarted){
			return;
		}
		
		showProgress(getString(R.string.pending));
	}

	@Override
	public void onScanResults(WifiDisplay[] availableDisplays) {
		if(DEBUG){
			Log.d(TAG, "onScanResults() - available WifiDisplay:" + (availableDisplays==null ? "null" : availableDisplays.length));
			LogText("onScanResults() - available WifiDisplay:" + (availableDisplays==null ? "null" : availableDisplays.length));
		}
	}

	@Override
	public void onScanFinished() {
		if(DEBUG){
			Log.d(TAG, "onScanFinished()");
			LogText("onScanFinished()");
		}
	}

	@Override
	public void onPeerOpening() {
		if(DEBUG){
			Log.d(TAG, "onPeerOpening()");
			LogText("onPeerOpening()");
		}
		
//		createSurfaceView();
		
		progressDialog.setText(getString(R.string.connection_open));
//		txtState.setText(peer.deviceName + " " + getString(R.string.connecting));
	}

	@Override
	public void onPeerConnecting() {
		if(DEBUG){
			Log.d(TAG, "onPeerConnecting()");
			LogText("onPeerConnecting()");
		}
		
		createSurfaceView();
		
		progressDialog.setText(getString(R.string.connecting));
//		txtState.setText(peer.deviceName + " " + getString(R.string.connecting));
	}
	
	@Override
	public void onPeerDisconnecting() {
		if(!isStarted){
			return;
		}
		
		controller.requestDisconnect();
		showProgress(getString(R.string.disconnecting));
	}
	
	@Override
	public void onPeerConnectionFailure() {
		controller.requestDisconnect();
		Toast.makeText(mContext, getString(R.string.connection_failure), Toast.LENGTH_LONG).show();
	}
	

	@Override
	public void onPeerConnectingTimeout() {
		if(DEBUG){
			Log.d(TAG, "onPeerConnectingTimeout()");
		}
		
//		if (controller != null)
//			controller.requestDisconnect();
		restart();
		
		Toast.makeText(mContext, getString(R.string.connecting_timeout), Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onPeerConnected(WifiP2pDevice peer) {
		if(DEBUG){
			Log.d(TAG, "onPeerConnected() - peer:" + peer);
			LogText("onPeerConnected() - peer:" + peer);
		}		
		
		String dev = "";
		if(peer != null){
			dev = peer.deviceName;
		}
		
		hidePorgress();
		
		Toast.makeText(mContext, getString(R.string.connected) + " " + dev, Toast.LENGTH_SHORT).show();
		
		startLimitConnection();
	}
	
	@Override
	public void onPeerDisconnected(WifiP2pDevice peer) {
		
		// disconnected close app
		if (isCloseApp) {
			Log.d(TAG, "onPeerConnected() - close app");
			finish();
			return;
		}
		
		if (closeAppAfterDisconnected) {
			Log.d(TAG, "onPeerConnected() - disconnected! kill application");
			finish();
			return;
		}
		
		if(!isStarted){
			return;
		}
		
		String dev = "";
		if(peer != null){
			dev = peer.deviceName;
		}
		
		restart();
//		deleteSurfaceView();
		Toast.makeText(mContext, getString(R.string.disconnected) + " " + dev, Toast.LENGTH_SHORT).show();
		
		stopLimitConnection();
	}
	
	private void restart(){

		if (controller != null){
			controller.requestDisconnect();
			controller.requestStopScan();
			controller.requestStartScan();
		}
		
		deleteSurfaceView();
		showProgress(getString(R.string.pending));
		
	}
	
	
	
	@Override
	public boolean onKeyDown(int arg0, KeyEvent arg1) {
		if( arg1.getAction() == KeyEvent.ACTION_DOWN && arg1.getKeyCode() == KeyEvent.KEYCODE_BACK ){
			closeApplication();
			return true;
		}
		return super.onKeyDown(arg0, arg1);
	}



	private void showProgress(String msg){
		
//		if (sfv != null) {
//			sfv.setBackgroundColor(Color.BLACK);
//		}
		
		if (progressDialog == null) {
			progressDialog = new SmartSyncProgressDialog(this);
			progressDialog.setIndeterminate(true);
			progressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
				}
			});
			
			progressDialog.setOnKeyListener(new OnKeyListener() {
				@Override
				public boolean onKey(DialogInterface arg0, int arg1, KeyEvent arg2) {
					
					if( arg2.getAction() == KeyEvent.ACTION_DOWN && arg2.getKeyCode() == KeyEvent.KEYCODE_BACK ){
						
						if (controller.isDisconnected()) {
							controller.requestDisconnect();
							((Activity)mContext).finish();
						}
						else {
							closeApplication();
						}
						
//						((Activity)mContext).finish();
						
//						Log.v("SAPARK", "============================================= back ==");
//						new AlertDialog.Builder(mContext)
//							.setTitle(R.string.app_name)
//							.setMessage(R.string.exit_yn)
//							.setPositiveButton(R.string.yes, new OnClickListener() {
//								@Override
//								public void onClick(DialogInterface arg0, int arg1) {
//									Log.v("SAPARK", "++++++++++++++++++++++++exit this App");
//									isStarted = false;
//									((Activity)mContext).finish();
//								}
//							})
//							.setNegativeButton(R.string.no,	new OnClickListener() {
//								
//								@Override
//								public void onClick(DialogInterface arg0, int arg1) {
//									Log.v("SAPARK", "++++++++++++++++++++++++exit this cancel");
//								}
//							}).show();
					}
					
					return false;
				}
			});
			progressDialog.setCancelable(false);
			progressDialog.setCanceledOnTouchOutside(false);
		}		
		
		if (!progressDialog.isShowing()) {
			progressDialog.show();
		}	
		
		progressDialog.setText(msg);
		
		// limit
		showLimitDialog();
	}
	
	private void hidePorgress(){
		if (sfv != null) {
			sfv.setBackgroundColor(Color.TRANSPARENT);
		}
		
		if( progressDialog == null || !progressDialog.isShowing() ){
			return;
		}
		progressDialog.dismiss();
	}

	/*************************************************
	 * 
	 */
	private MSCMsgReceiver mMSCReceiver = null;
	private boolean isNetwork = false;
	public void startMSCMsgReceiver(){
		if( mMSCReceiver == null ){
			IntentFilter intentFilter = new IntentFilter();
			intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
			mMSCReceiver = new MSCMsgReceiver();
			registerReceiver(mMSCReceiver, intentFilter);
		}
	}
	public void stopMSCMsgReceiver(){
		if( mMSCReceiver != null ){
			this.unregisterReceiver(mMSCReceiver);
			mMSCReceiver = null;
		}
	}	

	class MSCMsgReceiver extends BroadcastReceiver {
	
		@Override
		public void onReceive(Context arg0, Intent intent) 
		{
			
			Log.i(TAG, "+++++++++++++++++++++++++++++++ Network::onReceive() : " + intent.getAction());
			
			if( intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION) )
			{
				Log.i(TAG, "@@ >> CONNECTIVITY_ACTION ");
				
				
				ConnectivityManager manager = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
				Log.v(TAG, ">>>>>>>> " + manager);
				Log.v(TAG, ">>>>>>>> " + manager.getTetheredIfaces());
				
				NetworkInfo ethernet = manager.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
				
				if( ethernet.isConnected() ) {
					isNetwork = true;
					Log.i(TAG, "@@ >> CONNECTIVITY_ACTION : Ethernet UP.");
				} else {
					
					NetworkInfo wlan = manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
					if( wlan.isConnected() ) {
						
						isNetwork = true;
						Log.i(TAG, "@@ >> CONNECTIVITY_ACTION : WIFI UP.");
						return;
					}
					
					isNetwork = false;
					Log.i(TAG, "@@ >> CONNECTIVITY_ACTION : Ethernet DOWN.");
				}
			}
		}
		
	}


	AlertDialog mLimitDialog = null;
	Handler	mLimitConnection = new Handler();
	boolean mLimitDialogShow = false;
	Runnable mLimitRunnable = new Runnable() {
		@Override
		public void run() {
			Log.i(TAG, ">>>>>>>>>>>>>>>>> Limited version timeout");
			
			controller.requestDisconnect();
			
			mLimitDialogShow = true;
		}
	};
	
	private void showLimitDialog() {
		if (!mLimitDialogShow) {
			return;
		}
		
		if (mLimitDialog == null) {
			AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
			builder.setTitle(R.string.limit_title);
			builder.setMessage(R.string.limit_msg);
			builder.setPositiveButton(R.string.ok, new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int arg1) {
					dialog.dismiss();
				}
			});
			mLimitDialog = builder.create();
		}
		
		if (mLimitDialog.isShowing()) {
			mLimitDialog.dismiss();
		}
		
		mLimitDialog.show();
		mLimitDialogShow = false;
	}
	
	private void startLimitConnection() {
		long time = 5 * 60 * 1000;	// 5 min
		mLimitConnection.postDelayed(mLimitRunnable, time);
	}
	
	private void stopLimitConnection() {
		mLimitDialogShow = false;
		mLimitConnection.removeCallbacks(mLimitRunnable);
	}

}
