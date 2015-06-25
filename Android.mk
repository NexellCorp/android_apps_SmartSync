LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
        $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := SmartSync
LOCAL_CERTIFICATE := platform

LOCAL_JNI_SHARED_LIBRARIES := libWifiDirect_Miracast
LOCAL_JAVA_LIBRARIES := javax.obex telephony-common mms-common
LOCAL_STATIC_JAVA_LIBRARIES := com.android.vcard

# If this is an unbundled build (to install seprately) then include
# the libraries in the APK, otherwise just put them in /system/lib and
# leave them out of the APK
ifneq (,$(TARGET_BUILD_APPS))
  LOCAL_JNI_SHARED_LIBRARIES := libWifiDirect_Miracast
else
  LOCAL_REQUIRED_MODULES := libWifiDirect_Miracast
endif

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
