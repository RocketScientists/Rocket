/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.home;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.mozilla.focus.R;
import org.mozilla.focus.home.model.Site;

import java.util.ArrayList;
import java.util.List;

class TopSiteAdapter extends RecyclerView.Adapter<SiteViewHolder> {

    final List<Site> sites = new ArrayList<>();
    final View.OnClickListener clickListener;
    final View.OnLongClickListener longClickListener;

    TopSiteAdapter(@NonNull List<Site> sites,
                   @Nullable View.OnClickListener clickListener,
                   @Nullable View.OnLongClickListener longClickListener) {
        this.sites.addAll(sites);
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @Override
    public SiteViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_top_site, parent, false);

        return new SiteViewHolder(view);
    }

    @Override
    public void onBindViewHolder(SiteViewHolder holder, int position) {
        final Site site = sites.get(position);
        holder.text.setText(site.getTitle());
        holder.img.setImageResource(site.getIconRes());

        // let click listener knows which site is clicked
        holder.itemView.setTag(site);

        if (clickListener != null) {
            holder.itemView.setOnClickListener(clickListener);
        }
        if (longClickListener != null) {
            holder.itemView.setOnLongClickListener(longClickListener);
        }
    }

    @Override
    public int getItemCount() {
        return sites.size();
    }

    public void addSite(int index, @NonNull Site toAdd) {
       this.sites.add(index, toAdd);
        notifyItemInserted(index);
    }

    public void removeSite(@NonNull Site toRemove) {
        for (int i = 0; i < this.sites.size(); i++) {
            final Site site = this.sites.get(i);
            if (site.getId() == toRemove.getId()) {
                this.sites.remove(i);
                notifyItemRemoved(i);
            }
        }
    }
}
