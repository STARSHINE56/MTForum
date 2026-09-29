package com.solosu.mtforum.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.solosu.mtforum.R;
import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.ui.widget.FrostedGlassDrawable;

import java.util.ArrayList;
import java.util.List;

/** 横向置顶帖子小卡片适配器。 */
public class StickyThreadAdapter extends RecyclerView.Adapter<StickyThreadAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(Thread thread);
    }

    private final Context context;
    private final List<Thread> items = new ArrayList<>();
    private OnItemClickListener listener;

    public StickyThreadAdapter(Context context) {
        this.context = context;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<Thread> threads) {
        items.clear();
        if (threads != null) items.addAll(threads);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_sticky_thread, parent, false);
        float density = context.getResources().getDisplayMetrics().density;
        view.setBackground(FrostedGlassDrawable.create(context, 10f));
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Thread thread = items.get(position);
        holder.title.setText(thread.getTitle() == null ? "" : thread.getTitle());
        String author = thread.getAuthor() == null ? "" : thread.getAuthor();
        String time = thread.getPublishTime() == null ? "" : thread.getPublishTime();
        holder.author.setText(author.isEmpty() ? time : (time.isEmpty() ? author : author + " · " + time));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(thread);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.itemView.getBackground().setVisible(true, false);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        holder.itemView.getBackground().setVisible(false, false);
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        holder.itemView.getBackground().setVisible(false, false);
        super.onViewRecycled(holder);
    }



    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView author;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_sticky_item_title);
            author = itemView.findViewById(R.id.tv_sticky_item_author);
        }
    }
}