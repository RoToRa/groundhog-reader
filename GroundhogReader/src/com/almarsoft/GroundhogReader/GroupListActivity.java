package com.almarsoft.GroundhogReader;

import java.lang.reflect.Method;
import java.util.Vector;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import com.almarsoft.GroundhogReader.lib.DBHelper;
import com.almarsoft.GroundhogReader.lib.DBUtils;
import com.almarsoft.GroundhogReader.lib.FSUtils;
import com.almarsoft.GroundhogReader.lib.ServerManager;
import com.almarsoft.GroundhogReader.lib.UsenetConstants;

public class GroupListActivity extends ListActivity {
    /** Activity showing the list of subscribed groups. */
	
	
	private final String MAGIC_GROUP_ADD_STRING = "Subscribe to group...";
	
	private static final int MENU_ITEM_MARKALLREAD = 1;
	private static final int MENU_ITEM_UNSUBSCRIBE = 2;

	private static final int ID_DIALOG_DELETING = 0;
	private static final int ID_DIALOG_UNSUBSCRIBING = 1;
	private static final int ID_DIALOG_MARKREAD = 2;

	// Real name of the groups, used for calling the MessageListActivity with the correct name
	private String[] mGroupsArray;
	private String mTmpSelectedGroup;
	
	// Name of the group + unread count, used for the listView arrayAdapter
	private String[] mGroupsWithUnreadCountArray;
	
	// This is a member so we can interrupt its operation, but be carefull to create it just
	// before the operation and assign to null once it has been used (at the start of the callback, not in the next line!!!)
	private GroupMessagesDownloadDialog mDownloader = null;
	private ServerManager mServerManager;
	private final Handler mHandler = new Handler();

	private boolean mOfflineMode;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	super.onCreate(savedInstanceState);
    	
		registerForContextMenu(this.getListView());
		mServerManager = new ServerManager(getApplicationContext());		
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		// Detect first-time usage and show help
		boolean firstTime = prefs.getBoolean("firstTime", true);
		
		if (firstTime) {
			Editor ed = prefs.edit();
			ed.putBoolean("firstTime", false);
			ed.commit();
			startActivity(new Intent(GroupListActivity.this, HelpActivity.class));
		}
		
		mOfflineMode = prefs.getBoolean("offlineMode", false);
		
		if (mOfflineMode) 
			setTitle("Group List (offline mode)");
		else 
			setTitle("Group List (online mode)");
		
    }

    
    @Override
    protected void onResume() {
    	super.onResume();
    	
    	Log.d(UsenetConstants.APPNAME, "GroupList onResume");
		
		// =====================================================
        // Try to detect server hostname changes in the settings
    	// =====================================================
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		if (prefs.getBoolean("hostChanged", false)) {
			// The host  has changed in the prefs, show the dialog and clean the group headers
			new AlertDialog.Builder(GroupListActivity.this)
			.setTitle("Group headers")
			.setMessage("Server change detected - the old headers have been deleted")
		    .setNeutralButton("Close", null)
		    .show();	
			
			DBUtils.restartAllGroupsMessages(getApplicationContext());
			
			// Finally remote the "dirty" mark and repaint the screen
			Editor editor = prefs.edit();
			editor.putBoolean("hostChanged", false);
			editor.commit();
			
		}
			
		Log.d(UsenetConstants.APPNAME, "onResume, recreating ServerManager");
		if (mServerManager == null)
			mServerManager = new ServerManager(getApplicationContext());
		
        //=======================================================================
        // Load the group names and unreadcount from the subscribed_groups table
        //=======================================================================
    	updateGroupList();
		
    }
    
	@Override
	protected void onPause() {
		super.onPause();
	
		Log.d(UsenetConstants.APPNAME, "GroupListActivity onPause");
		
		if (mDownloader != null) 
			mDownloader.interrupt();
		
		
    	if (mServerManager != null) 
    		mServerManager.stop();
    	mServerManager = null;
	}    
	
	
	@Override
	protected Dialog onCreateDialog(int id) {
		if(id == ID_DIALOG_DELETING){
			ProgressDialog loadingDialog = new ProgressDialog(this);
			loadingDialog.setMessage("Deleting...");
			loadingDialog.setIndeterminate(true);
			loadingDialog.setCancelable(true);
			return loadingDialog;
			
		} else if(id == ID_DIALOG_UNSUBSCRIBING){
			ProgressDialog loadingDialog = new ProgressDialog(this);
			loadingDialog.setMessage("Unsubscribing and deleting caches...");
			loadingDialog.setIndeterminate(true);
			loadingDialog.setCancelable(true);
			return loadingDialog;
			
		} else if(id == ID_DIALOG_MARKREAD){
			ProgressDialog loadingDialog = new ProgressDialog(this);
			loadingDialog.setMessage("Marking all messages as read and deleting read messages cache...");
			loadingDialog.setIndeterminate(true);
			loadingDialog.setCancelable(true);
			return loadingDialog;
		}  		

		return super.onCreateDialog(id);
	}
	
    
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// ignore orientation change because it would cause the message list to
		// be reloaded
		super.onConfigurationChanged(newConfig);
	}

    
    public void updateGroupList() {
    	
    	// We're probably called from mDownloader, so clear it
    	if (mDownloader != null) 
    		mDownloader = null;
    	
		DBHelper db = new DBHelper(getApplicationContext());
		SQLiteDatabase dbWrite = db.getWritableDatabase();
		
		Cursor cur = dbWrite.rawQuery("SELECT name FROM subscribed_groups", null);
	
		cur.moveToFirst();
		int count = cur.getCount();
		
		String[] proxyGroupsArray = new String[count+1];
		String[] proxyGroupsUnreadCount = new String[count+1];
		
		String curGroupName;
		int unread;
		
		StringBuilder builder = new StringBuilder(80);
		
		for (int i = 0; i < count; i++) {
			curGroupName = cur.getString(0);
			proxyGroupsArray[i] = curGroupName;
			//unread = cur.getInt(1);
			unread = DBUtils.getGroupUnreadCount(curGroupName, getApplicationContext());
			
			if (unread == -1) 
				proxyGroupsUnreadCount[i] = proxyGroupsArray[i];
			else {              
				proxyGroupsUnreadCount[i] = builder
			                                .append(proxyGroupsArray[i])
			                                .append(" (")
			                                .append(unread)
			                                .append(')')
			                                .toString();
				builder.delete(0, builder.length());
			}
			cur.moveToNext();
		}
		
		cur.close(); dbWrite.close(); db.close();
		
		proxyGroupsArray[proxyGroupsArray.length-1] = MAGIC_GROUP_ADD_STRING;
		proxyGroupsUnreadCount[proxyGroupsUnreadCount.length-1] = MAGIC_GROUP_ADD_STRING;
		
		mGroupsWithUnreadCountArray = proxyGroupsUnreadCount;
		mGroupsArray = proxyGroupsArray;
		
		// Finally fill the list
        setListAdapter(new ArrayAdapter<String>(this, R.layout.grouplist_item, mGroupsWithUnreadCountArray));
        getListView().invalidateViews();
    }
    
	// ================================================
	// Menu setting
	// ================================================
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		
		new MenuInflater(getApplication()).inflate(R.menu.grouplistmenu, menu);
		return(super.onCreateOptionsMenu(menu));
		
	}
	
	
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {		
		
		MenuItem getAll = menu.findItem(R.id.grouplist_menu_getall);
		MenuItem offline = menu.findItem(R.id.grouplist_menu_offline);
		
		if (mOfflineMode) {
			getAll.setTitle("Sync Messages");
			getAll.setIcon(android.R.drawable.ic_menu_upload);
			offline.setTitle("Set Online Mode");
			offline.setIcon(android.R.drawable.presence_online);
			
		} else {
			getAll.setTitle("Get All Headers");
			getAll.setIcon(android.R.drawable.ic_menu_set_as);
			offline.setTitle("Set Offline Mode");
			offline.setIcon(android.R.drawable.presence_offline);
		}
		return (super.onPrepareOptionsMenu(menu));
	}
	
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.grouplist_menu_addgroups:
				startActivity(new Intent(GroupListActivity.this, SubscribeActivity.class));
				return true;
				
			case R.id.grouplist_menu_settings:
				startActivity(new Intent(GroupListActivity.this, OptionsActivity.class));
				return true;
				
			case R.id.grouplist_menu_getall:
				getAllMessages();
				return true;
				
			case R.id.grouplist_menu_offline:
				mOfflineMode = !mOfflineMode;
				SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
				Editor editor = prefs.edit();
				editor.putBoolean("offlineMode", mOfflineMode);
				editor.commit();
				
				if (mOfflineMode) 
					setTitle("Group List (offline mode)");
				else 
					setTitle("Group List (online mode)");
				return true;
				
			case R.id.grouplist_menu_clearcache:
				showClearCacheDialog();
				return true;
				
			case R.id.grouplist_menu_quickhelp:
				startActivity(new Intent(GroupListActivity.this, HelpActivity.class));
				return true;
		}
		return false;
	}
	
	
	private void showClearCacheDialog() {
		new AlertDialog.Builder(GroupListActivity.this)
		.setTitle("Clear Cache")
		.setMessage("Do you want to delete the program cache? All unread messages will be deleted!")
	    .setPositiveButton("Yes", 
	    	new DialogInterface.OnClickListener() {
	    		public void onClick(DialogInterface dlg, int sumthin) { 
	    			clearCache();
	    		} 
	        } 
	     )		     
	     .setNegativeButton("No", null)		     		    		 
	     .show();		
	}
	
	
	private void clearCache() {
		
		Thread cacheDeleterThread = new Thread() {
			
			public void run() {
				DBUtils.deleteAllMessages(GroupListActivity.this.getApplicationContext());
				FSUtils.deleteDirectory(UsenetConstants.EXTERNALSTORAGE + "/" + UsenetConstants.APPNAME + "/offlinecache/groups");
				FSUtils.deleteDirectory(UsenetConstants.EXTERNALSTORAGE + "/" + UsenetConstants.APPNAME + "/attachments");
				
				mHandler.post(new Runnable() { 
					public void run() {
						updateGroupList();
						dismissDialog(ID_DIALOG_DELETING);
						} 
					}
				);
			}
		};
		
		cacheDeleterThread.start();
		showDialog(ID_DIALOG_DELETING);
	}

	
	@SuppressWarnings("unchecked")
	private void getAllMessages() {
		
		int groupslen = mGroupsArray.length - 1;
		
		if (groupslen == 0) 
			return;
		
		Vector<String> groupVector = new Vector<String>(groupslen);
		
		for (int i=0; i < groupslen; i++)
			groupVector.add(mGroupsArray[i]);
		
		Class[] noargs = new Class[0];
		
		try {
			
			Method callback = this.getClass().getMethod("updateGroupList", noargs);
			
			mDownloader = new GroupMessagesDownloadDialog(mServerManager, this);
			mDownloader.synchronize(mOfflineMode, groupVector, callback, this);
			
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}		
	}
    
	// ==============================
	// Contextual menu on group
	// ==============================
	
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    	new MenuInflater(getApplicationContext()).inflate(R.menu.grouplist_item_menu, menu);
    	menu.setHeaderTitle("Group menu");
    	super.onCreateContextMenu(menu, v, menuInfo);
    }
    
    
    @Override
    public boolean onContextItemSelected(MenuItem item) {
    	
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        //HeaderItemClass header = mHeaderItemsList.get(info.position);
        final String groupname = mGroupsArray[info.position];
        
        
        if (groupname.equalsIgnoreCase(MAGIC_GROUP_ADD_STRING)) {
        	// Haha, nice try!
        	return true;
        }
        
        int order = item.getOrder();
        
    	// "Mark all as read" => Show confirm dialog and call markAllRead
    	if (order == MENU_ITEM_MARKALLREAD) {
			new AlertDialog.Builder(GroupListActivity.this)
			.setTitle("Mark all read")
			.setMessage("Do you want to mark all messages in " + groupname + " as read?")
		    .setPositiveButton("Yes", 
		    	new DialogInterface.OnClickListener() {
		    		public void onClick(DialogInterface dlg, int sumthin) { 
		    			markAllRead(groupname);
		    		} 
		        } 
		     )		     
		     .setNegativeButton("No", null)		     		    		 
		     .show();	
    		return true;
    	}
    	
    	// "Unsubscribe group" => Show confirm dialog and call unsubscribe
    	if (order == MENU_ITEM_UNSUBSCRIBE) {
			new AlertDialog.Builder(GroupListActivity.this)
			.setTitle("Unsubscribe")
			.setMessage("Do you want to unsubscribe from " + groupname + "?")
		    .setPositiveButton("Yes", 
		    	new DialogInterface.OnClickListener() {
		    		public void onClick(DialogInterface dlg, int sumthin) { 
		    			unsubscribe(groupname);
		    		} 
		        } 
		     )		     
		     .setNegativeButton("No", null)		     		    		 
		     .show();	
    		return true;
    	}
        return false;
    }
    
    
    private void markAllRead(final String group) {
    	Thread readMarkerThread = new Thread() {
    		public void run() {
	    		DBUtils.groupMarkAllRead(group, GroupListActivity.this.getApplicationContext());
	    		DBUtils.deleteReadMessages(GroupListActivity.this.getApplicationContext());
	    		
	    		mHandler.post(new Runnable() {
	    			public void run() {
	    				updateGroupList();
	    				dismissDialog(ID_DIALOG_MARKREAD);
	    			}
	    		});
    		}	
    	};
    	
    	readMarkerThread.start();
    	showDialog(ID_DIALOG_MARKREAD);
    }
    
    
    private void unsubscribe(final String group) {
    	
    	Thread unsubscribeThread = new Thread() {
    		public void run() {
    			DBUtils.unsubscribeGroup(group, GroupListActivity.this.getApplicationContext());
    			
    			mHandler.post(new Runnable() {
    				public void run() {
    					updateGroupList();
    					dismissDialog(ID_DIALOG_UNSUBSCRIBING);
    					}
    				}
    			);
    		}
    	};
    	
    	unsubscribeThread.start();
    	showDialog(ID_DIALOG_UNSUBSCRIBING);
    }
    // ==================================================================================================
    // OnItem Clicked Listener (start the MessageListActivity and pass the clicked group name
    // ==================================================================================================

    
    // Dialog code in swing/android are soooooooooooooooooo ugly :(
    
    @Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
    	
		final String groupName = mGroupsArray[position];
		
		// If clicked on the "Add group" in the list, go to subscribe
		if (groupName.equalsIgnoreCase(MAGIC_GROUP_ADD_STRING)) {
			startActivity(new Intent(GroupListActivity.this, SubscribeActivity.class));
			
		} 
		else {
			
			mTmpSelectedGroup = groupName;
			
			// If in offlinemode, offer to synchronize uncatched messages first, if there is any
			if (mOfflineMode) {
				if (DBUtils.groupHasUncatchedMessages(mTmpSelectedGroup, getApplicationContext())) {
					new AlertDialog.Builder(GroupListActivity.this)
					.setTitle("Get new")
					.setMessage("Warning: the group has messages with headers but without contents (downloaded in" +
							    " online mode.) Do you want to get the contents now? (if you don't, the content" +
							    " will be fetched from the net when you click on a message, even in offline mode)")
					
				    .setPositiveButton("Yes (sync)", 
				    	new DialogInterface.OnClickListener() {
				    	
				    		@SuppressWarnings("unchecked")
							public void onClick(DialogInterface dlg, int sumthin) {
				    			Vector<String> groupVector = new Vector<String>(1);
				    			groupVector.add(mTmpSelectedGroup);
				    			
								try {
									Class[] noargs = new Class[0];
									// This will be called after the synchronize from mDownloader:
									Method callback = GroupListActivity.this.getClass().getMethod("fetchFinishedStartMessageList", noargs);
									mDownloader    = new GroupMessagesDownloadDialog(mServerManager, GroupListActivity.this);
									mDownloader.synchronize(true, groupVector, callback, GroupListActivity.this);
								} catch (SecurityException e) {
									e.printStackTrace();
								} catch (NoSuchMethodException e) {
									e.printStackTrace();
								}
				    			
				    		} 
				        } 
				     )		     
				     .setNegativeButton("No (enter anyway)",
				        new DialogInterface.OnClickListener() {
				    	 	public void onClick(DialogInterface dlg, int sumthin) {
				    	 		fetchFinishedStartMessageList();
				    	 	}
				     	}
				     )		     		    		 
				     .show();
				} else {
					fetchFinishedStartMessageList();
				}
				
			} else { // online, ask about updating
				 
				new AlertDialog.Builder(GroupListActivity.this)
				.setTitle("Get new")
				.setMessage("Do you want to fetch new headers for " + mTmpSelectedGroup + "?")
				
			    .setPositiveButton("Yes", 
			    	new DialogInterface.OnClickListener() {
			    		@SuppressWarnings("unchecked")
						public void onClick(DialogInterface dlg, int sumthin) {
			    			Vector<String> groupVector = new Vector<String>(1);
			    			groupVector.add(mTmpSelectedGroup);
			    			
							try {
								Class[] noargs = new Class[0];
								Method callback = GroupListActivity.this.getClass().getMethod("fetchFinishedStartMessageList", noargs);
								mDownloader    = new GroupMessagesDownloadDialog(mServerManager, GroupListActivity.this);
								mDownloader.synchronize(false, groupVector, callback, GroupListActivity.this);
							} catch (SecurityException e) {
								e.printStackTrace();
							} catch (NoSuchMethodException e) {
								e.printStackTrace();
							}
			    			
			    		} 
			        } 
			     )		     
			     .setNegativeButton("No",
			        new DialogInterface.OnClickListener() {
			    	 	public void onClick(DialogInterface dlg, int sumthin) {
			    	 		fetchFinishedStartMessageList();
			    	 	}
			     	}
			     )		     		    		 
			     .show();	
			}
		}
    }
    
    
    public void fetchFinishedStartMessageList() {
    	if (mDownloader != null)
    		mDownloader = null;
    	Intent msgList = new Intent(GroupListActivity.this, MessageListActivity.class);
    	msgList.putExtra("selectedGroup", mTmpSelectedGroup);
    	startActivity(msgList);
    }
}

