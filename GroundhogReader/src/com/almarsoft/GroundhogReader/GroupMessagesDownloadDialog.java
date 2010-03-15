package com.almarsoft.GroundhogReader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Vector;

import org.apache.commons.net.nntp.NNTPNoSuchMessageException;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import com.almarsoft.GroundhogReader.lib.DBUtils;
import com.almarsoft.GroundhogReader.lib.FSUtils;
import com.almarsoft.GroundhogReader.lib.ServerAuthException;
import com.almarsoft.GroundhogReader.lib.ServerManager;
import com.almarsoft.GroundhogReader.lib.UsenetConstants;
import com.almarsoft.GroundhogReader.lib.UsenetReaderException;

public class GroupMessagesDownloadDialog {
	
	private static final int NOT_FINISHED = 0;
	private static final int FINISHED_ERROR = 1;
	protected static final int FINISHED_ERROR_AUTH = 2;
	protected static final int FETCH_FINISHED_OK = 3;
	private static final int FINISHED_INTERRUPTED = 4;
	private static final int POST_FINISHED_OK = 5;
	
	private final String TIP = "\nTIP: ALT+DEL will delete a line when replying";
	
	private ServerManager mServerManager;
	private Context mContext;
	private String mError = "";
	private int mLimit;
	private ProgressDialog mProgress;
	
	private Thread mServerGetterThread = null;
	private Thread mServerPosterThread = null;
	
	private final Handler mHandler = new Handler();
	private PowerManager.WakeLock mWakeLock = null;

	// Used to pass information from synchronize() to the other two method-threads
	private Method mCallback = null;
	private Object mCallerInstance = null;
	private boolean mTmpOfflineMode = false;
	private Vector<String> mTmpGroups = null;


	public GroupMessagesDownloadDialog(ServerManager manager, Context context) {
		mServerManager = manager;
		mContext = context;
		PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK|PowerManager.ON_AFTER_RELEASE, "GroundhogDownloading");
	}
	
	
	public void interrupt() {
    	if (mServerGetterThread != null && mServerGetterThread.isAlive()) {
			mServerGetterThread.interrupt();
			mServerGetterThread = null;
    	}
    	
    	if (mServerPosterThread != null && mServerPosterThread.isAlive()) {
    		mServerPosterThread.interrupt();
    		mServerPosterThread = null;
    	}
    	
    	if (mWakeLock.isHeld()) mWakeLock.release();
	}
	
	
	public void synchronize(boolean offlineMode, final Vector<String> groups, Method callback, Object caller) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
		mLimit = new Integer(prefs.getString("maxFetch", "100").trim());
		mCallback = callback;
		mCallerInstance = caller;
		mTmpOfflineMode = offlineMode;
		mTmpGroups = groups;
	
		mWakeLock.acquire();
		postPendingOutgoingMessages();
	}
	

	// ========================================================
	// Get the messages from the server and store them on the
	// DB.
	// ========================================================
	public void getArticleInfosFromServer() {
		
		final boolean offlineMode = mTmpOfflineMode;
		final Vector<String> groups = mTmpGroups;

		mServerGetterThread = new Thread() {

			// ========================================================
			// Main thread activity, get the messages and update the UI
			// ========================================================
			public void run() {
				
				String group = "[group]";
				String typeFetch;
				SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
				
				try {
					int groupslen = groups.size();
					
					for (int i=0; i<groupslen; i++) {
						group = groups.get(i);
						
						String status = "Asking for new articles";
						
						updateStatus(group, status, NOT_FINISHED, 0, mLimit);
						mServerManager.selectNewsGroupConnecting(group);

						long lastFetched, firstToFetch;
						lastFetched = DBUtils.getGroupLastFetchedNumber(group, mContext);
						
						// First time for this group, keep the -1 so getArticleNumbers knows what to do, but if it's not the 
						// first time, get the lastFetched + 1 as the firstToFetch
						if (lastFetched == -1) 
							firstToFetch = lastFetched;
						else 
							firstToFetch = lastFetched + 1; 
						
						Vector<Long> articleList = mServerManager.getArticleNumbers(firstToFetch, mLimit);
						
						if (offlineMode) {
							// Get a vector with the server article numbers of articleInfos downloaded (and unread) but
							// not catched, then join the two vectors. It's very important than the newly adquired articles
							// from the server go at the end because the number of the last fetched message
							// is taken from the last element. This is done so we also get the content of these messages
							// when the user syncs in offline mode
							Vector<Long> alreadyGotArticleList = DBUtils.getUnreadNoncatchedArticleList(group, mContext);
							
							for (Long artNumber2 : articleList) 
								alreadyGotArticleList.add(artNumber2);
							
							articleList = alreadyGotArticleList;
						}
							
						if (mTmpOfflineMode) 
							typeFetch = "full messages";
						else                 
							typeFetch = "headers";
						
						status = "Getting " + typeFetch;
						
						int len = articleList.size();
						updateStatus(group, status, NOT_FINISHED, 0, len);
						
						long msgid, number;
						String server_msg_id;
						Vector<Object> offlineData;
						int articleListLen = articleList.size();
						
						for (int j=0; j < articleListLen; j++) {
							
							number = articleList.get(j);
							
							if (isInterrupted()) {
								updateStatus(group, "Interrupted", FINISHED_INTERRUPTED, 100, 100);
								this.stop();
								return;
							}
	
							updateStatus(group, status, NOT_FINISHED, j, len);
							
							// Check if the articleInfo is already on the DB (this can happen when the user has 
							// selected sync after a non-offline "Get New Messages"; in this case we download only
							// the content but don't do the fetching-and-inserting operation, obviously
							
							offlineData = DBUtils.isHeaderInDatabase(number, group, mContext);
							
							// Wasn't on the DB, get and insert it
							if (offlineData == null) { 
								
								offlineData = mServerManager.getAndInsertArticleInfo(number, prefs.getString("readDefaultCharset", "ISO8859-15"));
							}
							
							// Offline mode: save also the article contents to the cache
							if (offlineMode) {
								msgid = (Long) offlineData.get(0);
								server_msg_id = (String) offlineData.get(1);
								
								try {
									mServerManager.getHeader(msgid, server_msg_id, false, false);
									mServerManager.getBody  (msgid, server_msg_id, false, false);
								} catch (NNTPNoSuchMessageException e) {
									// Message not in server, mark as read and ignore
									e.printStackTrace();
									DBUtils.markAsRead(number, mContext);
									mServerManager.selectNewsGroupConnecting(group);
									continue;
								}
							}
						}
	
						if (articleListLen > 0) {
							DBUtils.storeGroupLastFetchedMessageNumber(group, articleList.lastElement(), mContext);
						}
						
						if (groups.lastElement().equalsIgnoreCase(group))
							updateStatus(group, status, FETCH_FINISHED_OK, len, len);
					}
				} catch (IOException e) {
					mError = e.toString() + " " + group;
					updateStatus("Error", "Error", FINISHED_ERROR, 0, 100);
					e.printStackTrace();
					this.stop();
					return;
				} catch (UsenetReaderException e) {
					mError = e.toString() + " " + group;
					updateStatus("Error", "Error", FINISHED_ERROR, 0, 100);
					e.printStackTrace();
					this.stop();
					return;
				} catch (ServerAuthException e) {
					mError = e.toString() + " " + group;;
					updateStatus("Auth error", "Error", FINISHED_ERROR_AUTH, 0, 100);
					e.printStackTrace();
					this.stop();
					return;
				}
			}
		};
		
		mServerGetterThread.start();
	}
	
	// =========================================
	// The name says it all :)
	private void postPendingOutgoingMessages() {
		
		mServerPosterThread = new Thread() {
			public void run() {
				
				try {
					String postTitle = "Posting";
					String postCont  = "Posting pending messages";
					
					
					Vector<Long> pendingIds = DBUtils.getPendingOutgoingMessageIds(mContext);
					int pendingSize = pendingIds.size();
					
					if (pendingIds == null || pendingSize == 0) {
						updateStatus(postTitle, "Finished", POST_FINISHED_OK, 0, 0);
						this.stop();
						return; 
					}
					
					String basePath = UsenetConstants.EXTERNALSTORAGE + "/" + UsenetConstants.APPNAME + "/offlinecache/outbox/";
					String msgPath;
					String message;
					
					updateStatus(postTitle, postCont, NOT_FINISHED, 0, pendingSize);
					
					for (int i=0; i<pendingSize; i++) {
						
						if (isInterrupted()) {
							updateStatus(postTitle, "Interrupted", FINISHED_INTERRUPTED, 100, 100);
							this.stop();
							return;
						}
						
						long pId = pendingIds.get(i);
						msgPath = basePath + Long.toString(pId);
						try {
							message = FSUtils.loadStringFromDiskFile(msgPath, false);
							mServerManager.postArticle(message, true);
							updateStatus(postTitle, postCont, NOT_FINISHED, i, pendingSize);
						} catch (UsenetReaderException e) {
							// Message not found for some reason, just skip but delete from DB
						}
						FSUtils.deleteOfflineSentPost(pId, mContext);
						
					}
					FSUtils.deleteDirectory(UsenetConstants.EXTERNALSTORAGE + "/" + UsenetConstants.APPNAME + "/offlinecache/outbox");
					
					updateStatus(postTitle, postCont, POST_FINISHED_OK, pendingSize, pendingSize);
					this.stop();
					return;
					
					
				} catch (IOException e) {
					mError = e.toString() + " (posting)";
					updateStatus("Error", "Error", FINISHED_ERROR, 0, 100);
					e.printStackTrace();
					this.stop();
					return;
				} catch (ServerAuthException e) {
					mError = e.toString() + " (posting)";
					updateStatus("Auth error", "Error", FINISHED_ERROR_AUTH, 0, 100);
					e.printStackTrace();
					this.stop();
					return;
				}
			}
		};
		
		
		mProgress = new ProgressDialog(mContext);
		mProgress.setMessage("" + TIP);
		mProgress.setTitle(mTmpGroups.firstElement());
		mProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		mProgress.setMax(mLimit);
		mProgress.show();
		mServerPosterThread.start();
	}
	
	// =========================================================
	// Sent an update the the UI (progress dialog) from a thread
	// =========================================================

    
	private void updateStatus(final String group, final String textStatus, final int threadStatus, final int current, final int total) {
		
    	mHandler.post(new Runnable() { 
    		public void run() { 
    			updateResultsInUi(textStatus, threadStatus, group, current, total); 
    			} 
    		}
    	);
	}
	

	// ===================================================================================
	// UI updater from Threads; check the status, progress and message and
	// display them
	// Also: Downloader thread finished => Call the loading of messages from the
	// DB thread
	// Loading of Msgs from DB finished => Set the listview adapter to display
	// messages
	// ===================================================================================
	//updateResultsInUi(textStatus, threadStatus, group, current, total);
	
	private void dismissDialog(ProgressDialog d) {
		if (d != null) {
			try {
				d.dismiss();
				// XXX YYY ZZZ: Ver si se puede hacer algo para que no se vaya el dialogo
			} catch (IllegalArgumentException e) {}
			
		}
	}
	
	private void updateResultsInUi(String textStatus, int threadStatus, String currentGroup, int current, int total) {
		

		if (mProgress != null) {
			mProgress.setMessage(textStatus + TIP);
			mProgress.setTitle(currentGroup);
			mProgress.setMax(total);
			mProgress.setProgress(current);
		}

		if (threadStatus == FINISHED_ERROR) {
			if (mWakeLock.isHeld()) mWakeLock.release();
			if (mProgress != null) 
				dismissDialog(mProgress);

			mServerGetterThread = null;
			mServerPosterThread = null;
			

			new AlertDialog.Builder(mContext)
					.setTitle("Error")
					.setMessage("There was an error trying to get or post the messages. Please check your connection settings:\n" + mError)
					.setNeutralButton("Close", null)
					.show();
			
		} 
		else if (threadStatus == FINISHED_INTERRUPTED) {
			if (mWakeLock.isHeld()) mWakeLock.release();
			if(mProgress != null)
				dismissDialog(mProgress);
			
			mServerGetterThread = null;
			mServerPosterThread = null;
			
			new AlertDialog.Builder(mContext)
				.setTitle("Interrupted")
				.setMessage("Download interrupted (Android sent me to sleep, probably)")
				.setNeutralButton("Close", null)
				.show();

		}
		else if (threadStatus == FINISHED_ERROR_AUTH) {
			if (mWakeLock.isHeld()) mWakeLock.release();
			if (mProgress != null) 
				dismissDialog(mProgress);

			mServerGetterThread = null;
			mServerPosterThread = null;
			
			
			new AlertDialog.Builder(mContext)
					.setTitle("Auth Error")
					.setMessage( "There was an error trying to authenticate. Please check your user and password with your server:\n" + mError)
					.setNeutralButton("Close", null)
					.show();
			
		}
		else if (threadStatus == POST_FINISHED_OK) {
			// Posting of pending messages finished, now get the new message or headerInfos
			mServerPosterThread = null;
			
			getArticleInfosFromServer();

		}
		else if (threadStatus == FETCH_FINISHED_OK) {
			if (mWakeLock.isHeld()) mWakeLock.release();
			// The fetching of messages or headers has finished, call the provided callback if != null
			if (mProgress != null)
				dismissDialog(mProgress);
			
			mServerGetterThread = null;
			mServerPosterThread = null;
			mTmpGroups = null;
			mTmpOfflineMode = false;
			
			if (mCallback != null && mCallerInstance != null) {
				try {
					Object[] noparams = new Object[0];
					mCallback.invoke(mCallerInstance, noparams);
				} catch (IllegalArgumentException e) {
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				} catch (InvocationTargetException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
