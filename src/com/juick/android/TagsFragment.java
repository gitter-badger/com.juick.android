/*
 * Juick
 * Copyright (C) 2008-2012, Ugnich Anton
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.juick.android;

import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.support.v4.app.SupportActivity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import com.juick.android.datasource.JuickCompatibleURLMessagesSource;
import com.juick.android.datasource.MessagesSource;
import org.json.JSONArray;

import java.io.File;

/**
 *
 * @author Ugnich Anton
 */
public class TagsFragment extends ListFragment implements OnItemClickListener, OnItemLongClickListener {

    private TagsFragmentListener parentActivity;
    private int uid = 0;

    @Override
    public void onAttach(SupportActivity activity) {
        super.onAttach(activity);
        try {
            parentActivity = (TagsFragmentListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement TagsFragmentListener");
        }
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            uid = args.getInt("uid", 0);
            if (uid == 0) {
                MessagesSource messagesSource = (MessagesSource)args.get("messagesSource");
                if (messagesSource instanceof JuickCompatibleURLMessagesSource) {
                    JuickCompatibleURLMessagesSource jcums = (JuickCompatibleURLMessagesSource)messagesSource;
                    String user_idS = jcums.getArg("user_id");
                    if (user_idS != null) {
                        try {
                            uid = Integer.parseInt(user_idS);
                        } catch (Throwable _) {}
                    }
                }
            }
        }

        getListView().setOnItemClickListener(this);
        getListView().setOnItemLongClickListener(this);

        MessagesFragment.installDividerColor(getListView());
        MainActivity.restyleChildrenOrWidget(view);

        Thread thr = new Thread(new Runnable() {

            public void run() {
                String url = "http://api.juick.com/tags";
                File globalTagsCache = new File(view.getContext().getCacheDir(), "global_tags-"+uid+".json");
                String cachedString = null;
                if (uid != 0) {
                    url += "?user_id=" + uid;
                }
                if (globalTagsCache.exists() && globalTagsCache.lastModified() > System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L) {
                    cachedString = XMPPService.readFile(globalTagsCache);
                }
                final String jsonStr = cachedString != null ? cachedString : Utils.getJSON(getActivity(), url, null).getResult();
                if (jsonStr != null && cachedString == null) {
                    XMPPService.writeStringToFile(globalTagsCache, jsonStr);
                }
                if (isAdded()) {
                    getActivity().runOnUiThread(new Runnable() {

                        public void run() {
                            if (jsonStr != null) {
                                try {
                                    ArrayAdapter<String> listAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_list_item_1) {
                                        @Override
                                        public View getView(int position, View convertView, ViewGroup parent) {
                                            View retval = super.getView(position, convertView, parent);
                                            MainActivity.restyleChildrenOrWidget(retval);
                                            return retval;    //To change body of overridden methods use File | Settings | File Templates.
                                        }
                                    };

                                    JSONArray json = new JSONArray(jsonStr);
                                    int cnt = json.length();
                                    for (int i = 0; i < cnt; i++) {
                                        listAdapter.add(json.getJSONObject(i).getString("tag"));
                                    }
                                    setListAdapter(listAdapter);
                                } catch (Exception e) {
                                    Log.e("initTagsAdapter", e.toString());
                                }
                            }
                        }
                    });
                }
            }
        });
        thr.start();
        MainActivity.restyleChildrenOrWidget(getListView());

    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        parentActivity.onTagClick((String) getListAdapter().getItem(position), uid);
    }

    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        parentActivity.onTagLongClick((String) getListAdapter().getItem(position), uid);
        return true;
    }

    public interface TagsFragmentListener {

        public void onTagClick(String tag, int uid);

        public void onTagLongClick(String tag, int uid);
    }
}
