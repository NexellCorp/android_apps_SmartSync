package com.example.android.wifidirect;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

public class SinkPlayer {
	
	
	private Surface surface;
	private SurfaceHolder surfaceholder;
	private String mhost;
	private int mport;

	public SinkPlayer() {
		super();
		
	}

	public void setHolder(SurfaceHolder sh) {
		surfaceholder = sh;
		if (null != sh) {
			surface = surfaceholder.getSurface();
			Log.d("zzl:::", "create surface");
		} else {
			surface = null;
		}
	}

	public void setHostAndPort(String host, int port) {
		mhost = host;
		mport = port;
	}

	public void startRtsp() {
		setRtspSink(surface, mhost, mport);
	}
	
	public void stopRtsp() {
		stop();
	}

	private native void setRtspSink(Surface surface, String host, int port);
	private native void stop();

	static {

		System.loadLibrary("stagefright_wfd");
		System.loadLibrary("WifiDirect_Miracast");
	}

}
