package com.avos.avoscloud;

import android.annotation.SuppressLint;
import android.content.Context;

import com.avos.avoscloud.AVIMOperationQueue.Operation;
import com.avos.avoscloud.PendingMessageCache.Message;
import com.avos.avoscloud.SignatureFactory.SignatureException;
import com.avos.avoscloud.im.v2.AVIMClient;
import com.avos.avoscloud.im.v2.AVIMMessage;
import com.avos.avoscloud.im.v2.AVIMOptions;
import com.avos.avoscloud.im.v2.Conversation.AVIMOperation;
import com.avos.avospush.push.AVWebSocketListener;
import com.avos.avospush.session.CommandPacket;
import com.avos.avospush.session.ConversationAckPacket;
import com.avos.avospush.session.ConversationControlPacket;
import com.avos.avospush.session.ConversationQueryPacket;
import com.avos.avospush.session.MessageReceiptCache;
import com.avos.avospush.session.SessionControlPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by yangchaozhng on 3/28/14.
 */
@SuppressLint("NewApi")
public class AVSession {

  static final int OPERATION_OPEN_SESSION = 10004;
  static final int OPERATION_CLOSE_SESSION = 10005;
  static final int OPERATION_UNKNOW = -1;

  public static final String ERROR_INVALID_SESSION_ID = "Null id in session id list.";

  /**
   * 用于 read 的多端同步
   */
  private final String LAST_NOTIFY_TIME = "lastNotifyTime";

  /**
   * 用于 patch 的多端同步
   */
  private final String LAST_PATCH_TIME = "lastPatchTime";

  /**
   * 用于存储相关联的 AVUser 的 sessionToken
   */
  private final String AVUSER_SESSION_TOKEN = "avuserSessionToken";

  private final Context context;
  private final String selfId;
  String tag;
  private String userSessionToken = null;
  private String realtimeSessionToken = null;
  private long realtimeSessionTokenExpired = 0l;
  private long lastNotifyTime = 0;
  private long lastPatchTime = 0;

  final AtomicBoolean sessionOpened = new AtomicBoolean(false);
  final AtomicBoolean sessionPaused = new AtomicBoolean(false);
  // 标识是否需要从缓存恢复
  final AtomicBoolean sessionResume = new AtomicBoolean(false);

  private final AtomicLong lastServerAckReceived = new AtomicLong(0);

  PendingMessageCache<Message> pendingMessages;
  AVIMOperationQueue conversationOperationCache;
  private final ConcurrentHashMap<String, AVConversationHolder> conversationHolderCache =
      new ConcurrentHashMap<String, AVConversationHolder>();

  // responsable for session event(open/close/resume/error)
  final AVSessionListener sessionListener;
  // responsable for im protocol: directCommand/sessionCommand, etc
  private final AVWebSocketListener websocketListener;

  /**
   * 离线消息推送模式
   * true 为仅推送数量，false 为推送具体消息
   */
  private static boolean onlyPushCount = false;

  public AVWebSocketListener getWebSocketListener() {
    return this.websocketListener;
  }

  public AVSession(String selfId, AVSessionListener sessionListener) {
    this.selfId = selfId;
    this.context = AVOSCloud.applicationContext;
    this.sessionListener = sessionListener;
    this.websocketListener = new AVDefaultWebSocketListener(this);
    pendingMessages = new PendingMessageCache<Message>(selfId, Message.class);
    conversationOperationCache = new AVIMOperationQueue(selfId);
  }

  /**
   * open a new session
   *
   * @param parcel
   * @param requestId
   */
  public void open(final AVIMClientParcel parcel, final int requestId) {
    this.tag = parcel.getClientTag();
    updateUserSessionToken(parcel.getSessionToken());
    try {
      if (PushService.isPushConnectionBroken()) {
        sessionListener
            .onError(AVOSCloud.applicationContext, AVSession.this, new IllegalStateException(
                "Connection Lost"), OPERATION_OPEN_SESSION, requestId);
        return;
      }

      if (sessionOpened.get()) {
        this.sessionListener.onSessionOpen(context, this, requestId);
        return;
      }

      openWithSignature(requestId, parcel.isReconnection(), true);
    } catch (Exception e) {
      sessionListener.onError(AVOSCloud.applicationContext, this, e,
          OPERATION_OPEN_SESSION, requestId);
    }
  }

  void reopen() {
    String rtmSessionToken = AVSessionCacheHelper.IMSessionTokenCache.getIMSessionToken(getSelfPeerId());
    if (!AVUtils.isBlankString(rtmSessionToken)) {
      openWithSessionToken(rtmSessionToken);
    } else {
      int requestId = AVUtils.getNextIMRequestId();
      openWithSignature(requestId, true, false);
    }
  }

  public void renewRealtimeSesionToken(final int requestId) {
    final SignatureCallback callback = new SignatureCallback() {
      @Override
      public void onSignatureReady(Signature sig, AVException exception) {
        if (null != exception) {

          LogUtil.log.d("failed to generate signaure. cause:", exception);
        } else {
          SessionControlPacket scp = SessionControlPacket.genSessionCommand(
              getSelfPeerId(), null,
              SessionControlPacket.SessionControlOp.RENEW_RTMTOKEN, sig,
              getLastNotifyTime(), getLastPatchTime(), requestId);
          scp.setTag(tag);
          scp.setSessionToken(realtimeSessionToken);
          PushService.sendData(scp);
        }
      }

      @Override
      public Signature computeSignature() throws SignatureException {
        SignatureFactory signatureFactory = AVIMOptions.getGlobalOptions().getSignatureFactory();
        if (null == signatureFactory && !AVUtils.isBlankString(getUserSessionToken())) {
          signatureFactory = new AVUserSignatureFactory(getUserSessionToken());
        }
        if (null != signatureFactory) {
          return signatureFactory.createSignature(getSelfPeerId(), new ArrayList<String>());
        }
        return null;
      }
    };
    // 在某些特定的rom上，socket回来的线程会与Service本身的线程不一致，导致AsyncTask调用出现异常
    if (!AVUtils.isMainThread()) {
      AVOSCloud.handler.post(new Runnable() {
        @Override
        public void run() {
          new SignatureTask(callback).commit(getSelfPeerId());
        }
      });
    } else {
      new SignatureTask(callback).commit(getSelfPeerId());
    }

  };

  void updateRealtimeSessionToken(String sessionToken, int expireInSec) {
    this.realtimeSessionToken = sessionToken;
    this.realtimeSessionTokenExpired = System.currentTimeMillis() + expireInSec * 1000;
    AVIMClient client = AVIMClient.getInstance(this.selfId);
    if (null != client) {
      client.updateRealtimeSessionToken(sessionToken, realtimeSessionTokenExpired);
    }
    if (AVUtils.isBlankString(sessionToken)) {
      AVSessionCacheHelper.IMSessionTokenCache.removeIMSessionToken(getSelfPeerId());
    } else {
      AVSessionCacheHelper.IMSessionTokenCache.addIMSessionToken(getSelfPeerId(), sessionToken,
          realtimeSessionTokenExpired);
    }
  }

  /**
   * 使用 im-sessionToken 来登录
   */
  private void openWithSessionToken(String rtmSessionToken) {
    SessionControlPacket scp = SessionControlPacket.genSessionCommand(
        this.getSelfPeerId(), null, SessionControlPacket.SessionControlOp.OPEN,
        null, this.getLastNotifyTime(), this.getLastPatchTime(), null);
    scp.setSessionToken(rtmSessionToken);
    scp.setReconnectionRequest(true);
    PushService.sendData(scp);
  }

  /**
   * 使用签名登陆
   */
  private void openWithSignature(final int requestId, final boolean reconnectionFlag,
                                 final boolean notifyListener) {
    final SignatureCallback callback = new SignatureCallback() {
      @Override
      public void onSignatureReady(Signature sig, AVException exception) {
        if (null != exception) {
          if (notifyListener) {
            sessionListener.onError(AVOSCloud.applicationContext, AVSession.this, exception,
                OPERATION_OPEN_SESSION, requestId);
          }
          LogUtil.log.d("failed to generate signaure. cause:", exception);
        } else {
          conversationOperationCache.offer(Operation.getOperation(
              AVIMOperation.CLIENT_OPEN.getCode(), getSelfPeerId(), null, requestId));
          SessionControlPacket scp = SessionControlPacket.genSessionCommand(
              getSelfPeerId(), null,
              SessionControlPacket.SessionControlOp.OPEN, sig,
              getLastNotifyTime(), getLastPatchTime(), requestId);
          scp.setTag(tag);
          scp.setReconnectionRequest(reconnectionFlag);
          PushService.sendData(scp);
        }
      }

      @Override
      public Signature computeSignature() throws SignatureException {
        SignatureFactory signatureFactory = AVIMOptions.getGlobalOptions().getSignatureFactory();
        if (null == signatureFactory && !AVUtils.isBlankString(getUserSessionToken())) {
          signatureFactory = new AVUserSignatureFactory(getUserSessionToken());
        }
        if (null != signatureFactory) {
          return signatureFactory.createSignature(getSelfPeerId(), new ArrayList<String>());
        }
        return null;
      }
    };
    // 在某些特定的rom上，socket回来的线程会与Service本身的线程不一致，导致AsyncTask调用出现异常
    if (!AVUtils.isMainThread()) {
      AVOSCloud.handler.post(new Runnable() {
        @Override
        public void run() {
          new SignatureTask(callback).commit(getSelfPeerId());
        }
      });
    } else {
      new SignatureTask(callback).commit(getSelfPeerId());
    }
  }

  public void close() {
    close(CommandPacket.UNSUPPORTED_OPERATION);
  }

  public void cleanUp() {
    updateRealtimeSessionToken("", 0);
    if (pendingMessages != null) {
      pendingMessages.clear();
    }
    if (conversationOperationCache != null) {
      this.conversationOperationCache.clear();
    }
    this.conversationHolderCache.clear();
    MessageReceiptCache.clean(this.getSelfPeerId());
  }

  protected void close(int requestId) {
    // session的close操作需要做到即便是不成功的，本地也要认为成功了

    try {
      // 都关掉了，我们需要去除Session记录
      AVSessionCacheHelper.getTagCacheInstance().removeSession(getSelfPeerId());
      AVSessionCacheHelper.IMSessionTokenCache.removeIMSessionToken(getSelfPeerId());

      // 如果session都已不在，缓存消息静静地等到桑田沧海
      this.cleanUp();

      if (!sessionOpened.compareAndSet(true, false)) {
        this.sessionListener.onSessionClose(context, this, requestId);
        return;
      }
      if (!sessionPaused.getAndSet(false)) {
          conversationOperationCache.offer(Operation.getOperation(
              AVIMOperation.CLIENT_DISCONNECT.getCode(), selfId, null, requestId));
        SessionControlPacket scp = SessionControlPacket.genSessionCommand(this.selfId, null,
          SessionControlPacket.SessionControlOp.CLOSE, null, requestId);
        PushService.sendData(scp);
      } else {
        // 如果网络已经断开的时候，我们就不要管它了，直接强制关闭吧
        this.sessionListener.onSessionClose(context, this, requestId);
      }
    } catch (Exception e) {
      sessionListener.onError(AVOSCloud.applicationContext, this, e,
          OPERATION_CLOSE_SESSION, requestId);
    }
  }

  protected void storeMessage(Message cacheMessage, int requestId) {
    pendingMessages.offer(cacheMessage);
    conversationOperationCache.offer(Operation.getOperation(
        AVIMOperation.CONVERSATION_SEND_MESSAGE.getCode(), getSelfPeerId(), cacheMessage.cid,
        requestId));
  }

  public String getSelfPeerId() {
    return this.selfId;
  }

  protected void setServerAckReceived(long lastAckReceivedTimestamp) {
    lastServerAckReceived.set(lastAckReceivedTimestamp);
  }

  protected void queryOnlinePeers(List<String> peerIds, int requestId) {
    SessionControlPacket scp =
        SessionControlPacket.genSessionCommand(this.selfId, peerIds,
            SessionControlPacket.SessionControlOp.QUERY, null, requestId);
    PushService.sendData(scp);
  }

  // TODO： need change to use REST API
  protected void conversationQuery(Map<String, Object> params, int requestId) {
    if (sessionPaused.get()) {
      RuntimeException se = new RuntimeException("Connection Lost");
      BroadcastUtil.sendIMLocalBroadcast(getSelfPeerId(), null, requestId, se,
          AVIMOperation.CONVERSATION_QUERY);
      return;
    }

    conversationOperationCache.offer(Operation.getOperation(
        AVIMOperation.CONVERSATION_QUERY.getCode(), selfId, null, requestId));

    PushService.sendData(ConversationQueryPacket.getConversationQueryPacket(getSelfPeerId(),
      params, requestId));
  }

/*
 * this method is only called by RTM v2 or above
 */
  public AVException checkSessionStatus() {
    if (!sessionOpened.get()) {
      return new AVException(AVException.OPERATION_FORBIDDEN,
          "Please call AVIMClient.open() first");
    } else if (sessionPaused.get()) {
      return new AVException(new RuntimeException("Connection Lost"));
    } else if (sessionResume.get()) {
      return new AVException(new RuntimeException("Connecting to server"));
    } else {
      return null;
    }
  }

  public AVConversationHolder getConversationHolder(String conversationId, int convType) {
    AVConversationHolder conversation = conversationHolderCache.get(conversationId);
    if (conversation != null) {
      return conversation;
    } else {
      conversation = new AVConversationHolder(conversationId, this, convType);
      AVConversationHolder elderObject =
          conversationHolderCache.putIfAbsent(conversationId, conversation);
      return elderObject == null ? conversation : elderObject;
    }
  }

  protected void removeConversation(String conversationId) {
    conversationHolderCache.remove(conversationId);
  }

  protected void createConversation(final List<String> members,
      final Map<String, Object> attributes,
      final boolean isTransient, final boolean isUnique, final boolean isTemp, final int tempTTL,
      final boolean isSystem, final int requestId) {
    if (sessionPaused.get()) {
      RuntimeException se = new RuntimeException("Connection Lost");
      sessionListener.onError(context, this, se, AVIMOperation.CONVERSATION_CREATION.getCode(),
          requestId);
      return;
    }
    SignatureCallback callback = new SignatureCallback() {

      @Override
      public void onSignatureReady(Signature sig, AVException e) {
        if (e == null) {
          conversationOperationCache.offer(Operation.getOperation(
              AVIMOperation.CONVERSATION_CREATION.getCode(), getSelfPeerId(), null, requestId));
          PushService.sendData(ConversationControlPacket.genConversationCommand(selfId, null,
              members, ConversationControlPacket.ConversationControlOp.START, attributes, sig,
              isTransient, isUnique, isTemp, tempTTL, isSystem, requestId));
        } else {
          BroadcastUtil.sendIMLocalBroadcast(getSelfPeerId(), null, requestId, e,
              AVIMOperation.CONVERSATION_CREATION);
        }
      }

      @Override
      public Signature computeSignature() throws SignatureException {
        SignatureFactory signatureFactory = AVIMOptions.getGlobalOptions().getSignatureFactory();
        if (signatureFactory != null) {
          return signatureFactory.createSignature(selfId, members);
        }
        return null;
      }
    };
    new SignatureTask(callback).commit(this.selfId);
  }

  /**
   * 设置离线消息推送模式
   * @param isOnlyCount
   */
  public static void setUnreadNotificationEnabled(boolean isOnlyCount) {
    onlyPushCount = isOnlyCount;
  }

  /**
   * 是否被设置为离线消息仅推送数量
   * @return
   */
  public static boolean isOnlyPushCount() {
    return onlyPushCount;
  }

  long getLastNotifyTime() {
    if (lastNotifyTime <= 0) {
      lastNotifyTime = AVPersistenceUtils.sharedInstance().getPersistentSettingLong(selfId,
          LAST_NOTIFY_TIME, 0L);
    }
    return lastNotifyTime;
  }

  void updateLastNotifyTime(long notifyTime) {
    long currentTime = getLastNotifyTime();
    if (notifyTime > currentTime) {
      lastNotifyTime = notifyTime;
      AVPersistenceUtils.sharedInstance().savePersistentSettingLong(selfId, LAST_NOTIFY_TIME, notifyTime);
    }
  }

  /**
   * 获取最后接收到 server patch 的时间
   * 按照业务需求，当本地没有缓存此数据时，返回最初始的客户端值
   * @return
   */
  long getLastPatchTime() {
    if (lastPatchTime <= 0) {
      lastPatchTime = AVPersistenceUtils.sharedInstance().getPersistentSettingLong(selfId,
          LAST_PATCH_TIME, 0L);
    }

    if (lastPatchTime <= 0) {
      lastPatchTime = System.currentTimeMillis();
      AVPersistenceUtils.sharedInstance().savePersistentSettingLong(selfId, LAST_PATCH_TIME, lastPatchTime);
    }
    return lastPatchTime;
  }

  void updateLastPatchTime(long patchTime) {
    long currentTime = getLastPatchTime();
    if (patchTime > currentTime) {
      lastPatchTime = patchTime;
      AVPersistenceUtils.sharedInstance().savePersistentSettingLong(selfId, LAST_PATCH_TIME, patchTime);
    }
  }

  String getUserSessionToken() {
    if (AVUtils.isBlankString(userSessionToken)) {
      userSessionToken = AVPersistenceUtils.sharedInstance().getPersistentSettingString(selfId,
          AVUSER_SESSION_TOKEN, "");
    }
    return userSessionToken;
  }

  void updateUserSessionToken(String token) {
    userSessionToken = token;
    if (!AVUtils.isBlankString(userSessionToken)) {
      AVPersistenceUtils.sharedInstance().savePersistentSettingString(selfId, AVUSER_SESSION_TOKEN,
          userSessionToken);
    }
  }

  /**
   * 确认客户端已经拉取到未推送到本地的离线消息
   * 因为没有办法判断哪些消息是离线消息，所以对所有拉取到的消息都发送 ack
   * @param messages
   * @param conversationId
   */
  public void sendUnreadMessagesAck(ArrayList<AVIMMessage> messages, String conversationId) {
    if (onlyPushCount && null != messages && messages.size() > 0) {
      Long largestTimeStamp = 0L;
      for (AVIMMessage message : messages) {
        if (largestTimeStamp < message.getTimestamp()) {
          largestTimeStamp = message.getTimestamp();
        }
      }
      PushService.sendData(ConversationAckPacket.getConversationAckPacket(getSelfPeerId(),
        conversationId, largestTimeStamp));
    }
  }
}
