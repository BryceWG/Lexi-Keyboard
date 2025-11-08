package com.brycewg.asrkb.aidl;

import com.brycewg.asrkb.aidl.SpeechConfig;
import com.brycewg.asrkb.aidl.ISpeechCallback;

interface IExternalSpeechService {
  /**
   * 启动一次语音识别会话。
   * @return 正整数 sessionId 表示成功；负数为错误码（-1: not_pro, -2: busy, -3: invalid, -4: no_permission）。
   */
  int startSession(in SpeechConfig config, ISpeechCallback callback);

  /** 主动停止录音，进入处理阶段（如有）。*/
  void stopSession(int sessionId);

  /** 取消并清理会话（不保证产生最终结果）。*/
  void cancelSession(int sessionId);

  /** 查询指定会话是否处于录音中。*/
  boolean isRecording(int sessionId);

  /** 是否存在任意活动会话。*/
  boolean isAnyRecording();

  /** 版本信息（语义化版本名）。*/
  String getVersion();
}

