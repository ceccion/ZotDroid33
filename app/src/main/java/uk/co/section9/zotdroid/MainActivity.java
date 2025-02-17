package uk.co.section9.zotdroid;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Html;
import android.view.ViewGroup;
import android.widget.ExpandableListAdapter;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
//import android.support.design.widget.NavigationView;
import com.google.android.material.navigation.NavigationView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

import me.maxwin.view.XListView;
import uk.co.section9.zotdroid.auth.ZoteroBroker;
import uk.co.section9.zotdroid.data.ZotDroidDB;
import uk.co.section9.zotdroid.data.zotero.Attachment;
import uk.co.section9.zotdroid.data.zotero.Author;
import uk.co.section9.zotdroid.data.zotero.Collection;
import uk.co.section9.zotdroid.data.zotero.Note;
import uk.co.section9.zotdroid.data.zotero.Record;
import uk.co.section9.zotdroid.data.zotero.Tag;
import uk.co.section9.zotdroid.ops.ZotDroidSyncOps;
import uk.co.section9.zotdroid.ops.ZotDroidUserOps;
import uk.co.section9.zotdroid.task.ZotDroidSyncCaller;
import uk.co.section9.zotdroid.task.ZotDroidWebDavCaller;

/**
 * TODO - if we cancel a sync, we need to not replace anything!
 */

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, ZoteroBroker.ZoteroAuthCallback,
        ZotDroidSyncCaller, ZotDroidWebDavCaller, XListView.IXListViewListener {

    public static final String      TAG = "zotdroid.MainActivity";
    private static int              ZOTERO_LOGIN_REQUEST = 1667;

    private Dialog                  _loading_dialog;
    private Dialog                  _download_dialog;
    private Dialog                  _init_dialog;
    private Dialog                  _tag_dialog;
    private Dialog                  _note_dialog;
    private ZotDroidUserOps         _zotdroid_user_ops;
    private ZotDroidSyncOps         _zotdroid_sync_ops;
    private ZotDroidListAdapter     _main_list_adapter;
    private XListView               _main_list_view;
    private Handler                 _handler;

    // Our main list memory locations
    ArrayList< String >                    _main_list_items = new ArrayList< String >  ();
    ArrayList< String >                    _main_list_collections = new ArrayList< String >  ();
    HashMap< String, ArrayList<String> >   _main_list_sub_items =  new HashMap< String, ArrayList<String> >();

    // Our current mapping, given search and similar. List ID to Record basically
    HashMap < Integer, Record>       _main_list_map = new HashMap<Integer, Record>();
    HashMap < Integer, Collection>   _collection_list_map = new HashMap<Integer, Collection>();

    /**
     * A small class that listens for Intents. Mostly used to change font size on the fly.
     */

    public class PreferenceChangeBroadcastReceiver extends BroadcastReceiver {
        public PreferenceChangeBroadcastReceiver () {}
        private static final String TAG = "PreferenceChangeBroadcastReceiver";
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == "FONT_SIZE_PREFERENCE_CHANGED"){ changeFontSize();}
        }
    }

    PreferenceChangeBroadcastReceiver _broadcast_receiver = new PreferenceChangeBroadcastReceiver();

    /**
     * onCreate as standard. Attempts to auth and if we arent authed, launches the login screen.
     *
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        Log.i(TAG,"Creating ZotDroid...");
        setContentView(R.layout.activity_main);

        // Setup the toolbar with the extra search
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        LayoutInflater inflater = LayoutInflater.from(this);
        View sl = inflater.inflate(R.layout.search, null);
        toolbar.addView(sl);

        // Magical runnable that performs re-layout when the list changes
        final Runnable run_layout = new Runnable() {
            public void run() {
                redrawRecordList();
                setDrawer();
                // Set the font preference stuff
                IntentFilter filter = new IntentFilter("FONT_SIZE_PREFERENCE_CHANGED");
                registerReceiver(_broadcast_receiver,filter);
            }
        };

        SearchView sv = (SearchView) findViewById(R.id.recordsearch);
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                _zotdroid_user_ops.search(query);
                runOnUiThread(run_layout);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                // I put a check in here to reset if everything is blank
                // Also initialise needs to have completed - sometimes this fires off when
                // it shouldn't
                if (_zotdroid_user_ops != null) {
                    if (newText.isEmpty()) {
                        _zotdroid_user_ops.reset();
                        runOnUiThread(run_layout);
                    }
                }
                return false;
            }
        });

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);

        toggle.syncState();

        // A runnable that goes ahead and loads more items into our main list
        final Runnable load_more_items = new Runnable() {
            public void run() {
                _zotdroid_user_ops.getMoreResults(Constants.PAGINATION_SIZE);
                runOnUiThread(run_layout);
            }
        };

        // Setup the main list of items
        _main_list_view = (XListView) findViewById(R.id.listViewMain);
        _main_list_view.setPullLoadEnable(false);
        _handler = new Handler();
        _main_list_view.setXListViewListener(this);

        // Pass this activity - ZoteroBroker will look for credentials
        ZoteroBroker.passCreds(this,this);
        Util.getDownloadDirectory(this); // Naughty, but we create the dir here too!
        launchInitDialog();

        // Start initialisation in a separate thread for now.
        Runnable run = new Runnable() {
            public void run() {
                // Start tracing the bootup
                //Debug.startMethodTracing("zotdroid_trace_startup");
                initialise();
                // Stop tracing here.
                //Debug.stopMethodTracing();
                _init_dialog.dismiss();
                runOnUiThread(run_layout);
            }
        };

        Thread thread = new Thread(null, run, "Background");
        thread.start();
    }

    /**
     * Function when the Special Scrolling Listview is refreshed
     * This does nothing for now but might do later
     */
    @Override
    public void onRefresh() {
        _handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                listLoaded();
            }
        }, 1000);
    }
    private void listLoaded() {
        _main_list_view.stopRefresh();
        _main_list_view.stopLoadMore();
        _main_list_view.setRefreshTime("-");
    }

    /**
     * Called when our special list view wants to load more things
     */
    @Override
    public void onLoadMore() {
        if(_zotdroid_user_ops.hasMoreResults()) {
            _handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    _zotdroid_user_ops.getMoreResults(Constants.PAGINATION_SIZE);
                    expandRecordList();
                    _main_list_adapter.notifyDataSetChanged();
                    listLoaded();
                }
            }, 2000);
        }
    }

    private void initialise(){
        ZotDroidApp app = (ZotDroidApp) getApplication();
        _zotdroid_user_ops = new ZotDroidUserOps(app.getDB(), this, app.getMem(), this);
        _zotdroid_sync_ops = new ZotDroidSyncOps(app.getDB(), this, app.getMem(), this);
        _zotdroid_user_ops.reset();
    }

    /**
     * Don't redraw completely, just check the size of the _main_list_items and add more
     */
    private void expandRecordList() {
        ZotDroidApp app = (ZotDroidApp) getApplication();
        ZotDroidMem mem = app.getMem();

        int idx = 0;
        for (Record record : mem._records) {
            if (idx >= _main_list_items.size()) {
                String tt = record.get_title();
                _main_list_map.put(new Integer(_main_list_items.size()), record);
                _main_list_items.add(tt);

                // TODO - this is also in the redraw method - duplicating :/
                // We add metadata first, followed by attachments (TODO - Add a divider?)
                ArrayList<String> tl = new ArrayList<String>();
                tl.add("Title: " + record.get_title());

                for (Author author : record.get_authors()) {
                    tl.add("Author: " + author.get_name());
                }

                tl.add("Date Added: " + record.get_date_added());
                tl.add("Date Modified: " + record.get_date_modified());
                String tags = "Tags:";

                for (Tag t : record.get_tags()) {
                    tags = tags + " " + t.get_name();
                }

                tl.add(tags);

                for (Note n : record.get_notes()) {
                    tl.add("Note: " +  n.get_note().subSequence(0,10) + "...");
                }

                for (Attachment attachment : record.get_attachments()) {
                    tl.add("Attachment:" + attachment.get_file_name());
                }

                _main_list_sub_items.put(tt, tl);
            }
            idx +=1;
        }

        _main_list_view.setPullLoadEnable(false);
        if (_zotdroid_user_ops.hasMoreResults()) {
            _main_list_view.setPullLoadEnable(true);
        }

    }

    /**
     * Delete all downloaded files
     */
    private void deleteAllFiles() {
        ZotDroidApp app = (ZotDroidApp) getApplication();
        ZotDroidDB db = app.getDB();

        int n = db.getNumRecords();
        Vector<Record> records = db.getRecords(n);
        for (Record record : records) {
            Vector<Attachment> attachments = db.getAttachmentsForRecord(record);
            for (Attachment attachment : attachments) {
                _zotdroid_user_ops.deleteAttachmentFile(attachment);
            }
        }
        Toast toast = Toast.makeText(MainActivity.this.getApplicationContext(),
                "Deleted all downloaded files", Toast.LENGTH_SHORT);
        toast.show();
    }

    /**
     * We redraw our record list completely, based on the information held in the ZotDroidMem 'pool'
     */
    private void redrawRecordList() {
        _main_list_items.clear();
        _main_list_map.clear();

        ZotDroidApp app = (ZotDroidApp) getApplication();
        ZotDroidMem mem = app.getMem();

        // Possibly a better way to pass font size but for now
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String font_size = "medium";
        font_size = settings.getString("settings_font_size",font_size);

        _main_list_adapter = new ZotDroidListAdapter(this,this, _main_list_items, _main_list_sub_items,font_size);
        _main_list_view.setAdapter(_main_list_adapter);
        expandRecordList();

        // What happens when we click on a subitem
        _main_list_view.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
                Record record = null;
                // TODO - Eventually we will replace TextView with some better class for this.
                int total = _main_list_adapter.getChildrenCount(groupPosition);
                // Overkill and messy ><
                int aidx = 0;
                for (aidx = 0; aidx < total; aidx++){
                    String tv = (String)_main_list_adapter.getChild(groupPosition,aidx);
                    if (tv.contains("Attachment")){
                        break;
                    }
                }

                int nidx = 0;
                for (nidx = 0; nidx < total; nidx++){
                    String tv = (String)_main_list_adapter.getChild(groupPosition,nidx);
                    if (tv.contains("Note")){
                        break;
                    }
                }
                String tv = (String)_main_list_adapter.getChild(groupPosition,childPosition);
                // This is a bit flimsy! :(
                if (tv.contains("Attachment")) {
                    record = _main_list_map.get(groupPosition);
                    if (record != null) {
                        launchDownloadDialog();
                        _zotdroid_user_ops.startAttachmentDownload(record, childPosition - aidx);
                    }
                } else if (tv.contains("Tags")) {
                    launchTagDialog(groupPosition);
                } else if (tv.contains("Note")) {
                    launchNoteDialog(groupPosition,childPosition - nidx);
                }

                return true;
            }
        });
        _main_list_view.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View childView, int flatPos, long id) {
               if (ExpandableListView.getPackedPositionType(id) == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
                   final ExpandableListAdapter adapter = ((ExpandableListView) parent).getExpandableListAdapter();
                   long packedPos = ((ExpandableListView) parent).getExpandableListPosition(flatPos);
                   int groupPosition = ExpandableListView.getPackedPositionGroup(packedPos);
                   int childPosition = ExpandableListView.getPackedPositionChild(packedPos);

                   int total = _main_list_adapter.getChildrenCount(groupPosition);
                   int aidx = 0;
                   for (aidx = 0; aidx < total; aidx++){
                       String tv = (String)_main_list_adapter.getChild(groupPosition,aidx);
                       if (tv.contains("Attachment")){
                           break;
                       }
                   }

                   Record record = null;
                   String tv = (String)_main_list_adapter.getChild(groupPosition,childPosition);
                   if (tv.contains("Attachment")) {
                       record = _main_list_map.get(groupPosition);
                       if (record != null) {
                           String name = record.get_attachments().elementAt(childPosition-aidx).get_file_name();

                           if (_zotdroid_user_ops.deleteAttachmentFile(record,
                                   childPosition - aidx)) {
                               Toast toast = Toast.makeText(MainActivity.this.getApplicationContext(),
                                       "Deleted " + name, Toast.LENGTH_SHORT);
                               toast.show();
                           } else {
                               Toast toast = Toast.makeText(MainActivity.this.getApplicationContext(),
                                       "Failed to delete " + name, Toast.LENGTH_SHORT);
                               toast.show();
                           }
                       }
                   }
                   return true;
               }
               return false;
           }
        });
    }

    /**
     * A handy function that loads our dialog to show we are loading.
     * TODO - needs messages to show what we are doing.
     * https://stackoverflow.com/questions/37038835/how-do-i-create-a-popup-overlay-view-in-an-activity-without-fragment
     * @return
     */
    private Dialog launchLoadingDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.fragment_loading);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(true);

        ProgressBar pb = (ProgressBar) dialog.findViewById(R.id.progressBarLoading);
        pb.setVisibility(View.VISIBLE);

        DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        int dialogWidth = (int)(displayMetrics.widthPixels * 0.85);
        int dialogHeight = (int)(displayMetrics.heightPixels * 0.85);
        dialog.getWindow().setLayout(dialogWidth, dialogHeight);
        Button cancelButton = (Button) dialog.findViewById(R.id.buttonCancelLoading);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                _zotdroid_sync_ops.stop();
                dialog.dismiss();
            }
        });

        dialog.show();
        return dialog;
    }

    private Dialog launchInitDialog() {
        final Dialog dialog = new Dialog(this);
        _init_dialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.fragment_init);
        dialog.setCanceledOnTouchOutside(false);
        ProgressBar pb = (ProgressBar) dialog.findViewById(R.id.progressBarInit);
        pb.setVisibility(View.VISIBLE);
        DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        int dialogWidth = (int)(displayMetrics.widthPixels * 0.85);
        int dialogHeight = (int)(displayMetrics.heightPixels * 0.85);
        dialog.getWindow().setLayout(dialogWidth, dialogHeight);
        dialog.show();
        return dialog;
    }

    protected void _addTag(Tag t, Record record, LinearLayout topview, int dialogWidth) {
        final Tag tag = t;
        final Record r = record;
        final LinearLayout ll = topview;
        final LinearLayout lt = new LinearLayout(this);
        lt.setOrientation(LinearLayout.HORIZONTAL);
        lt.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        final TextView tf = new TextView(this);
        tf.setText(tag.get_name());
        tf.setMinimumWidth((int)(dialogWidth * 0.65));
        lt.addView(tf);
        // Button for removal of tags
        final Button bf = new Button(this);
        bf.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                r.remove_tag(tag);
                _zotdroid_user_ops.commitRecord(r);
                ll.removeView(lt);
                // TODO - need to somehow refresh the main_list_view of tags as well
            }
        });
        bf.setText("-");
        lt.addView(bf);
        lt.setVisibility(View.VISIBLE);
        ll.addView(lt,0);
    }

    protected Dialog launchTagDialog(int record_index){
        final Dialog dialog = new Dialog(this);
        _tag_dialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.fragment_tags);
        dialog.setCanceledOnTouchOutside(true);
        DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        final int dialogWidth = (int)(displayMetrics.widthPixels * 0.75);
        int dialogHeight = (int)(displayMetrics.heightPixels * 0.75);
        dialog.getWindow().setLayout(dialogWidth, dialogHeight);

        final Record r = _main_list_map.get(record_index);
        final LinearLayout ll = (LinearLayout) dialog.findViewById(R.id.fragment_tags_list);

        Button qb = (Button) dialog.findViewById(R.id.fragment_tags_newtag_button);
        final TextView tv = (TextView) dialog.findViewById(R.id.fragment_tags_newtag);
        qb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Tag tt : r.get_tags()){
                    if (tt.get_name().equals(tv.getText().toString())){ return; }
                }

                Tag newtag = new Tag(tv.getText().toString(),r.get_zotero_key());
                r.add_tag(newtag);
                _zotdroid_user_ops.commitRecord(r); // Change to be synced
                _addTag(newtag,r,ll, dialogWidth);
            }
        });

        Button ab = (Button) dialog.findViewById(R.id.fragment_tags_quit);
        ab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                _tag_dialog.dismiss();
            }
        });
        for (Tag t : r.get_tags()){ _addTag(t,r,ll,dialogWidth); }
        dialog.show();
        return dialog;
    }

    protected Dialog launchNoteDialog(int record_index, int note_index){
        final Dialog dialog = new Dialog(this);
        _note_dialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.fragment_notes);
        dialog.setCanceledOnTouchOutside(true);
        DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        int dialogWidth = (int)(displayMetrics.widthPixels * 0.75);
        int dialogHeight = (int)(displayMetrics.heightPixels * 0.75);
        dialog.getWindow().setLayout(dialogWidth, dialogHeight);
        final Record r = _main_list_map.get(record_index);
        final Note n = r.get_notes().get(note_index);
        Button qb = (Button) dialog.findViewById(R.id.fragment_notes_quit);
        final TextView ll = (TextView) dialog.findViewById(R.id.fragment_notes_note);
        qb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Write back the notes
                n.set_note(ll.getText().toString());
                _zotdroid_user_ops.commitNote(n);
                // Later we will need to update this too.
                //_zotdroid_user_ops.commitRecord(r); // Change to be synced
                _note_dialog.dismiss();
            }
        });

        ll.setText(Html.fromHtml(n.get_note()));
        dialog.show();
        return dialog;
    }


    private void changeFontSize() {
        TextView groupTitle = (TextView) _main_list_view.findViewById(R.id.main_list_group);
        TextView groupSubText = (TextView) _main_list_view.findViewById(R.id.main_list_subtext);

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String font_size = settings.getString("settings_font_size","medium");

        if (groupTitle != null ) {
            if (font_size.contains("small")) {
                groupTitle.setTextAppearance(this, R.style.MainList_Title_Small);
            } else if (font_size.contains("medium")) {
                groupTitle.setTextAppearance(this, R.style.MainList_Title_Medium);
            } else if (font_size.contains("large")) {
                groupTitle.setTextAppearance(this, R.style.MainList_Title_Large);
            } else {
                groupTitle.setTextAppearance(this, R.style.MainList_Title_Medium);
            }
        }
        if (groupSubText != null ) {
            if (font_size.contains("small")){ groupSubText.setTextAppearance(this, R.style.MainList_SubText_Small);}
            else if (font_size.contains("medium")){ groupSubText.setTextAppearance(this, R.style.MainList_SubText_Medium);}
            else if (font_size.contains("large")) { groupSubText.setTextAppearance(this, R.style.MainList_SubText_Large);}
            else { groupSubText.setTextAppearance(this, R.style.MainList_SubText_Medium);}
        }

        // This is expensive but I think it's what we have to do really.
        _zotdroid_user_ops.reset();
        redrawRecordList();
        setDrawer();
    }

    /**
     * Launch a loading dialog for showing progress and the like
     * @return
     */

    private Dialog launchDownloadDialog() {
        final Dialog dialog = new Dialog(this);
        _download_dialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.fragment_downloading);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(true);
        DisplayMetrics displayMetrics = this.getResources().getDisplayMetrics();
        int dialogWidth = (int)(displayMetrics.widthPixels * 0.85);
        int dialogHeight = (int)(displayMetrics.heightPixels * 0.85);
        dialog.getWindow().setLayout(dialogWidth, dialogHeight);

        Button cancelButton = (Button) dialog.findViewById(R.id.buttonCancelDownload);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                _zotdroid_user_ops.stop();
            }
        });

        dialog.show();
        return dialog;
    }

    /**
     * Reset everything and do a full sync from scratch
     */
    protected void resetAndSync() {
        _loading_dialog = launchLoadingDialog();
        _zotdroid_sync_ops.resetAndSync();
    }

    /**
     * Do a standard, partial sync if we can, else resetAndSync
     */
    protected void sync() {
        if (!_zotdroid_sync_ops.sync()) {
            resetAndSync();
            return;
        }
        _loading_dialog = launchLoadingDialog();
    }

    protected void startTestWebDav() {
        _loading_dialog = launchLoadingDialog();
        String status_message = "Testing Webdav Connection.";
        TextView messageView = (TextView) _loading_dialog.findViewById(R.id.textViewLoading);
        messageView.setText(status_message);
        _zotdroid_user_ops.testWebDav();
    }


    public void onSyncProgress(float progress) {
        String status_message = "Syncing with Zotero: " + Float.toString( Math.round(progress * 100.0f)) + "% complete.";
        TextView messageView = (TextView) _loading_dialog.findViewById(R.id.textViewLoading);
        messageView.setText(status_message);
        Log.i(TAG,status_message);
    }

    public void onSyncFinish(boolean success, String message) {
        _zotdroid_user_ops.reset();
        redrawRecordList();
        setDrawer();
        _loading_dialog.dismiss();
        Log.i(TAG,"Sync Version: " + _zotdroid_sync_ops.getVersion());
    }

    /**
       LoginActivity returns with some data for us, but we write it to the
        shared preferences here.
     */

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG,"Returned from Zotero Login hopefully.");
        if (requestCode == ZOTERO_LOGIN_REQUEST) {
            if (resultCode == Activity.RESULT_OK ) {
                ZoteroBroker.setCreds(this);
            }
            finishActivity(ZOTERO_LOGIN_REQUEST);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        switch (id) {
            case R.id.action_settings:
                //this.startActivityForResult(new Intent(this, SettingsActivity.class), 1);
                Intent si = new Intent(this,SettingsActivity.class);
                startActivity(si);
                return true;
            case R.id.action_reset_sync:
                if (ZoteroBroker.isAuthed()) {
                    resetAndSync();
                } else {
                    Log.i(TAG,"Not authed. Performing OAUTH.");
                    Intent loginIntent = new Intent(this, LoginActivity.class);
                    loginIntent.setAction("zotdroid.LoginActivity.LOGIN");
                    this.startActivityForResult(loginIntent,ZOTERO_LOGIN_REQUEST);
                }
                return true;
            case R.id.action_sync:
                if (ZoteroBroker.isAuthed()) {
                    sync();
                } else {
                    Log.i(TAG,"Not authed. Performing OAUTH.");
                    Intent loginIntent = new Intent(this, LoginActivity.class);
                    loginIntent.setAction("zotdroid.LoginActivity.LOGIN");
                    this.startActivityForResult(loginIntent,ZOTERO_LOGIN_REQUEST);
                }
                return true;

            case R.id.action_test_webdav:
                startTestWebDav();
                return true;

            case R.id.action_delete_all:
                deleteAllFiles();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void recSetDrawer(Collection c, int level) {
        _collection_list_map.put(_main_list_collections.size(),c);
        String indent = "";
        for (int i = 0; i < level; i++) { indent += "..."; }
        _main_list_collections.add(indent + c.get_title());
        for (Collection cc : c.get_sub_collections()) {
            recSetDrawer(cc,level + 1);
        }
    }

    /**
     * A subroutine to set the left-hand collections drawer
     */
    public void setDrawer() {
        // Now create our lefthand drawer from the collections
        ListView drawer_list = (ListView) findViewById(R.id.left_drawer);
        _main_list_collections.clear();
        _collection_list_map.clear();
        _collection_list_map.put(new Integer(0),null);
        _main_list_collections.add("All");

        ZotDroidApp app = (ZotDroidApp) getApplication();
        ZotDroidMem mem = app.getMem();

        // Firstly, get the top level collections
        Vector<Collection> toplevels = new Vector<Collection>();
        for (Collection c : mem._collections) {
            if (!c.has_parent()){ toplevels.add(c); }
        }

        for (Collection c: toplevels){ recSetDrawer(c,0); }

        // Override the adapter so we can set the fontsize
        drawer_list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, _main_list_collections) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView tv = (TextView) row;
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this.getContext());
                String font_size = settings.getString("settings_font_size","medium");
                // Set fonts here too!
                if (font_size.contains("small")) {
                    tv.setTextAppearance(this.getContext(), R.style.SideList_Small);
                } else if (font_size.contains("medium")) {
                    tv.setTextAppearance(this.getContext(), R.style.SideList_Medium);
                } else if (font_size.contains("large")) {
                    tv.setTextAppearance(this.getContext(), R.style.SideList_Large);
                } else {
                    tv.setTextAppearance(this.getContext(), R.style.SideList_Medium);
                }

                return row;
            }
        });

        // On-click show only these items in a particular collection and set the title to reflect this.
        drawer_list.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3)
            {
                Collection filter = _collection_list_map.get(position);
                _zotdroid_user_ops.swapCollection(filter);
                redrawRecordList();

                Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
                if (filter != null) { toolbar.setTitle("ZotDroid: " + filter.get_title());}
                else { toolbar.setTitle("ZotDroid:");}
            }
        });
    }

    public void onDownloadProgress(float progress) {
        String status_message = "Progess: " + Float.toString(progress) + "%";
        TextView messageView = (TextView) _download_dialog.findViewById(R.id.textViewDownloading);
        messageView.setText(status_message);
        Log.i(TAG, status_message);
    }

    public void onDownloadFinish(final boolean success, final String message, final String filetype) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar pb = (ProgressBar) _download_dialog.findViewById(R.id.progressBarDownload);
                pb.setVisibility(View.INVISIBLE);

                if (!success) {
                    String status_message = "Error: " + message;
                    TextView messageView = (TextView) _download_dialog.findViewById(R.id.textViewDownloading);
                    messageView.setText(status_message);
                    Log.i(TAG, status_message);
                } else {

                    Intent intent = new Intent();
                    File ff7 = new File(message);

                    if (ff7.exists() && ff7.canRead()) {
                        intent.setAction(android.content.Intent.ACTION_VIEW);
                        Log.i(TAG, "Attempting to open " + message);
                        try {
                            Uri pdfURI = FileProvider.getUriForFile(MainActivity.this,
                                    "uk.co.section9.zotdroid.provider",
                                    ff7);
                            intent.setDataAndType(pdfURI, filetype);
                            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
                            //intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            _download_dialog.dismiss();
                            startActivity(intent);
                            _download_dialog.dismiss();
                        } catch (Exception e) {
                            Log.d(TAG, "Error opening file");
                            e.printStackTrace();
                        }

                        _main_list_view.invalidate();
                        _main_list_view.invalidateViews();
                        _main_list_view.refreshDrawableState();

                    } else {
                        String status_message = "Error: " + message + " does not appear to exist.";
                        TextView messageView = (TextView) _download_dialog.findViewById(R.id.textViewDownloading);
                        messageView.setText(status_message);
                        Log.i(TAG, status_message);
                    }
                }
            }
        });
    }

    /**
     * Called when a webdav test process finishes.
     * @param success
     * @param message
     */
    @Override
    public void onWebDavTestFinish(boolean success, String message) {
        String status_message = "Connection Failed: " + message;
        if (success) {
            status_message = "Connection succeded";
        }
        TextView messageView = (TextView) _loading_dialog.findViewById(R.id.textViewLoading);
        messageView.setText(status_message);
        Button button = (Button) _loading_dialog.findViewById(R.id.buttonCancelLoading);
        button.setText("Dismiss");
        ProgressBar pb = (ProgressBar) _loading_dialog.findViewById(R.id.progressBarLoading);
        pb.setVisibility(View.INVISIBLE);
    }

    /**
     * Called when we are checking authorisation of our tokens
     * @param result
     */
    @Override
    public void onAuthCompletion(boolean result) {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(_broadcast_receiver);
    }
}
