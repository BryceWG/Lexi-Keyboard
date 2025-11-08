package com.brycewg.asrkb.aidl;

interface ISpeechCallback {
  /** 会话状态变更：0=Idle, 1=Recording, 2=Processing, 3=Error */
  void onState(int sessionId, int state, String message);

  /** 流式中间结果 */
  void onPartial(int sessionId, String text);

  /** 最终结果 */
  void onFinal(int sessionId, String text);

  /** 错误（非致命/致命均可能回调） */
  void onError(int sessionId, int code, String message);

  /** 实时音量（0.0-1.0） */
  void onAmplitude(int sessionId, float amplitude);
}

