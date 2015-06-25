package com.ect.smartsync;

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

public class SmartSyncProgressDialog extends ProgressDialog {
	
	private AnimationDrawable anim;
	private TextView	tv;

	public SmartSyncProgressDialog(Context context) {
		super(context);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setContentView(R.layout.ss_progress_dialog);
		getWindow().setLayout(LayoutParams.FILL_PARENT,
                LayoutParams.FILL_PARENT);
		
		ImageView i = (ImageView)findViewById(R.id.img_ss_prog_anim);
		i.setBackgroundResource(R.drawable.ss_progress_anim);
		anim = (AnimationDrawable)i.getBackground();
		
		tv = (TextView)findViewById(R.id.txt_state);
		
		
		Log.v("SmartSyncProgressDialog", "findText() : " + tv);
	}
	
	public void setText(String txt){
		
		Log.v("SmartSyncProgressDialog", "findText() : " + findViewById(R.id.txt_state));
		if (tv == null){
			Log.v("SmartSyncProgressDialog", "setText() : null");
			return;
		}
		
		tv.setText(txt);
	}

	@Override 
	public void show() { 
		super.show(); 
		anim.start(); 
	} 
	
	@Override 
	public void dismiss() { 
		super.dismiss(); 
		anim.stop(); 
	} 

	
}
