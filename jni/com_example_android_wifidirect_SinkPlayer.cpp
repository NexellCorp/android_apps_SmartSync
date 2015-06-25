/*
 * com_example_android_wifidirect_SinkPlayer.cpp
 *
 *  Created on: 2014-6-10
 *      Author: think
 */

#include"jni.h"
#include<stdio.h>

#include"com_example_android_wifidirect_SinkPlayer.h"

#include <utils/RefBase.h>

#include "ALooper.h"
#include "AMessage.h"

#include "sink/WifiDisplaySink.h"
#include "ANetworkSession.h"
//#include <gui/ISurfaceTexture.h>
#include <gui/Surface.h>
 #include <android/native_window_jni.h>

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"

#include <gui/SurfaceComposerClient.h>
#include <gui/Surface.h>
#include <media/AudioTrack.h>
#include <media/ICrypto.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>


#include <utils/Log.h>
#define LOG_TAG "WifiDisplaySinkPlayerJni"


extern "C" {

using namespace android;

sp < WifiDisplaySink > sink = NULL;

JNIEXPORT void JNICALL Java_com_example_android_wifidirect_SinkPlayer_setRtspSink(
		JNIEnv *env, jobject thiz, jobject sf, jstring host, jint port) {

	if (host == NULL)
		return;

	const char *tmp = env->GetStringUTFChars(host, NULL);
	if (tmp == NULL)
		return;

	AString hostStr(tmp);
	env->ReleaseStringUTFChars(host, tmp);
	tmp = NULL;

	/*sp<ISurfaceTexture> new_st;
	sp<Surface> surface(android_view_Surface_getSurface(env,sf));
	if(surface != NULL)
	{
		new_st = surface->getSurfaceTexture();
	}
*/


	ANativeWindow* window = ANativeWindow_fromSurface(env, sf);
	android::Surface* surface = static_cast<android::Surface*>(window);	
	sp<IGraphicBufferProducer> bufferProducer = surface->getIGraphicBufferProducer();

	ALOGD("==== $$$$$$$$$$$$$$$$ 1");
	sp < ALooper > looper = new ALooper;
	ALOGD("$$$$$$$$$$$$$$$$ 2");
	sp < ANetworkSession > session = new ANetworkSession;
	ALOGD("$$$$$$$$$$$$$$$$ 3");
	//sp < WifiDisplaySink > sink = new WifiDisplaySink(session,new_st);
	//sp < WifiDisplaySink > sink = new WifiDisplaySink(0, session); // lesc0.
	sink = new WifiDisplaySink(0, session, bufferProducer ); // lesc0.
	ALOGD("$$$$$$$$$$$$$$$$ 4");
	session->start();
	ALOGD("$$$$$$$$$$$$$$$$ 5");
	looper->setName("media.player");
	looper->registerHandler(sink);
	sink->start(hostStr.c_str(), port);
	ALOGD("$$$$$$$$$$$$$$$$ 7");
	looper->start(true);
	ALOGD("$$$$$$$$$$$$$$$$ 8");

}

/*
 * Class:     com_example_android_wifidirect_SinkPlayer
 * Method:    stop
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_com_example_android_wifidirect_SinkPlayer_stop
  (JNIEnv *, jobject) {

	ALOGD("$$$$$$$$$$$$$$$$ stop() - call");
	if (sink != NULL) {
		ALOGD("$$$$$$$$$$$$$$$$ stop() - 0");
		sink->stop();
		sink.clear();
		sink = NULL;
		ALOGD("$$$$$$$$$$$$$$$$ stop() - sink clear");
	}
//	if (looper != NULL) {
//		ALOGD("$$$$$$$$$$$$$$$$ stop() - 1");
//		looper->stop();
//		looper.clear();
//		looper = NULL;
//		ALOGD("$$$$$$$$$$$$$$$$ stop() - clear");
//	}
}

}
