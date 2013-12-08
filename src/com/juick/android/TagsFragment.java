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

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.juick.android.juick.JuickCompatibleURLMessagesSource;
import com.juick.android.juick.JuickHttpAPI;
import com.juick.android.juick.MessagesSource;
import com.juickadvanced.R;
import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * @author Ugnich Anton
 */
public class TagsFragment extends Fragment  {

    private TagsFragmentListener parentActivity;
    private int uid = 0;
    private boolean multi = false;
    private boolean showMine = true;
    /**
     * whether it should persist mine/all selection
     */
    public boolean saveMineAll = false;
    private View myAll;
    SharedPreferences sp;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            parentActivity = (TagsFragmentListener) activity;
            sp = activity.getPreferences(Activity.MODE_PRIVATE);
            if (saveMineAll)
                showMine = sp.getBoolean("showMine", true);
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement TagsFragmentListener");
        }
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tags_fragment, null);
    }

    View myView;

    class TagOffsets {
        int offset;
        int end;
        public StyleSpan existingSpan;

        TagOffsets(int offset, int end) {
            this.offset = offset;
            this.end = end;
        }
    }

    class TagSort implements Comparable<TagSort> {
        String tag;
        int messages;

        TagSort(String tag, int messages) {
            this.tag = tag;
            this.messages = messages;
        }

        @Override
        public int compareTo(TagSort another) {
            return another.messages - messages;
        }
    }

    HashMap<String, TagOffsets> tagOffsets = new HashMap<String, TagOffsets>();

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myView = view;
        Bundle args = getArguments();
        myAll = (View)view.findViewById(R.id.myAll);
        myAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMine = !showMine;
                if (saveMineAll) {
                    sp.edit().putBoolean("showMine", showMine).commit();
                }
                reloadTags(view);
            }
        });
        if (args != null) {
            uid = args.getInt("uid", 0);
            multi = args.getBoolean("multi", false);
            if (uid == 0) {
                MessagesSource messagesSource = (MessagesSource) args.get("messagesSource");
                if (messagesSource instanceof JuickCompatibleURLMessagesSource) {
                    JuickCompatibleURLMessagesSource jcums = (JuickCompatibleURLMessagesSource) messagesSource;
                    String user_idS = jcums.getArg("user_id");
                    if (user_idS != null) {
                        try {
                            uid = Integer.parseInt(user_idS);
                        } catch (Throwable _) {
                        }
                    }
                }
            }
        }

//        getListView().setOnItemClickListener(this);
//        getListView().setOnItemLongClickListener(this);

//        MessagesFragment.installDividerColor(getListView());
        MainActivity.restyleChildrenOrWidget(view);
        final TextView progress = (TextView)myView.findViewById(R.id.progress);
        final View okbutton = myView.findViewById(R.id.okbutton);
        progress.setText(R.string.Loading___);
        okbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final TextView selected = (TextView)myView.findViewById(R.id.selected);
                String selectedText = selected.getText().toString();
                String[] split = selectedText.split(" ");
                for (String s : split) {
                    if (s.length() == 0) continue;
                    if (!s.startsWith("*") || s.length() < 2) {
                        Toast.makeText(getActivity(), getActivity().getString(R.string.TagsFormatError), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                parentActivity.onTagClick(selectedText, uid);
            }
        });

        reloadTags(view);
    }

    private void reloadTags(final View view) {
        final View selectedContainer = myView.findViewById(R.id.selected_container);
        final View progressAll = myView.findViewById(R.id.progress_all);
        Thread thr = new Thread(new Runnable() {

            public void run() {
                final int tagsUID = showMine ? uid : 0;
                String url = JuickHttpAPI.getAPIURL() + "tags";
                File globalTagsCache = new File(view.getContext().getCacheDir(), "tags-" + tagsUID + ".json");
                String cachedString = null;
                if (tagsUID != 0) { // -1 == mine
                    url += "?user_id=" + tagsUID;
                }
                if (globalTagsCache.exists() && globalTagsCache.lastModified() > System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L) {
                    cachedString = XMPPService.readFile(globalTagsCache);
                }
                final String jsonStr = cachedString != null ? cachedString : Utils.getJSON(getActivity(), url, null).getResult();
                if (jsonStr != null && cachedString == null) {
                    XMPPService.writeStringToFile(globalTagsCache, jsonStr);
                }
                if (isAdded()) {
                    final SpannableStringBuilder tagsSSB = new SpannableStringBuilder();
                    if (jsonStr != null) {
                        try {
                            JSONArray json = new JSONArray(jsonStr);
                            int cnt = json.length();
                            ArrayList<TagSort> sortables = new ArrayList<TagSort>();
                            for (int i = 0; i < cnt; i++) {
                                final String tagg = json.getJSONObject(i).getString("tag");
                                final int messages = json.getJSONObject(i).getInt("messages");
                                sortables.add(new TagSort(tagg, messages));
                            }
                            Collections.sort(sortables);
                            HashMap<String, Double> scales = new HashMap<String, Double>();
                            for (int sz = 0, sortablesSize = sortables.size(); sz < sortablesSize; sz++) {
                                TagSort sortable = sortables.get(sz);
                                if (sz < 10) {
                                    scales.put(sortable.tag, 2.0);
                                } else if (sz < 20) {
                                    scales.put(sortable.tag, 1.5);
                                }
                            }
                            int start = 0;
                            if (getArguments().containsKey("add_system_tags")) {
                                start = -4;
                            }
                            for (int i = start; i < cnt; i++) {
                                final String tagg;
                                switch (i) {
                                    case -4: tagg = "public"; break;
                                    case -3: tagg = "friends"; break;
                                    case -2: tagg = "notwitter"; break;
                                    case -1: tagg = "readonly"; break;
                                    default: tagg = json.getJSONObject(i).getString("tag"); break;

                                }
                                int index = tagsSSB.length();
                                tagsSSB.append("*" + tagg);
                                tagsSSB.setSpan(new URLSpan(tagg) {
                                    @Override
                                    public void onClick(View widget) {
                                        onTagClick(tagg, tagsUID);
                                    }
                                }, index, tagsSSB.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                Double scale = scales.get(tagg);
                                if (scale != null) {
                                    tagsSSB.setSpan(new RelativeSizeSpan((float)scale.doubleValue()), index, tagsSSB.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                }
                                tagOffsets.put(tagg, new TagOffsets(index, tagsSSB.length()));
                                tagsSSB.append(" ");

                            }
                        } catch (Exception ex) {
                            tagsSSB.append("Error: " + ex.toString());
                        }
                    }
                    if (getActivity() != null) {
                        // maybe already closed?
                        getActivity().runOnUiThread(new Runnable() {

                            public void run() {
                                TextView tv = (TextView)myView.findViewById(R.id.tags);
                                progressAll.setVisibility(View.GONE);
                                if (multi)
                                    selectedContainer.setVisibility(View.VISIBLE);
                                tv.setText(tagsSSB, TextView.BufferType.SPANNABLE);
                                tv.setMovementMethod(LinkMovementMethod.getInstance());
                                MainActivity.restyleChildrenOrWidget(view);
                                final TextView selected = (TextView)myView.findViewById(R.id.selected);
                                selected.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }
            }
        });
        thr.start();
    }

    ArrayList<String> selectedTags = new ArrayList<String>();
    private void onTagClick(String tagg, int uid) {
        if (!multi) {
            parentActivity.onTagClick(tagg, uid);
        } else {
            TextView tv = (TextView)myView.findViewById(R.id.tags);
            Spannable text = (Spannable)tv.getText();
            TagOffsets tagOffsets1 = tagOffsets.get(tagg);
            if (!selectedTags.remove(tagg)) {
                if (selectedTags.size() == 5) {
                    Toast.makeText(getActivity(), getString(R.string.TooManyTags), Toast.LENGTH_LONG).show();
                    return;
                } else {
                    selectedTags.add(tagg);
                    tagOffsets1.existingSpan = new StyleSpan(Typeface.BOLD);
                    text.setSpan(tagOffsets1.existingSpan, tagOffsets1.offset, tagOffsets1.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                text.removeSpan(tagOffsets1.existingSpan);
                tagOffsets1.existingSpan = null;
            }
            final TextView selected = (TextView)myView.findViewById(R.id.selected);
            StringBuilder sb = new StringBuilder();
            for (String selectedTag : selectedTags) {
                sb.append("*");
                sb.append(selectedTag);
                sb.append(" ");
            }
            selected.setText(sb.toString().trim());
        }
    }

    public interface TagsFragmentListener {

        public void onTagClick(String tag, int uid);

        public void onTagLongClick(String tag, int uid);
    }
}
