package com.buddycloud.model;

import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.apache.http.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.os.AsyncTask;

import com.buddycloud.http.BuddycloudHTTPHelper;
import com.buddycloud.log.Logger;
import com.buddycloud.model.dao.PostsDAO;
import com.buddycloud.model.dao.ThreadsDAO;
import com.buddycloud.preferences.Preferences;
import com.buddycloud.utils.TimeUtils;

public class PostsModel extends AbstractModel<JSONArray, JSONObject, String> {

	private static final String TAG = PostsModel.class.getName();
	private static PostsModel instance;
	private static final int REMOTE_PAGE_SIZE = 10;
	private static final int LOCAL_PAGE_SIZE = 10;
	
	private static final String POSTS_ENDPOINT = "/content/posts";
	private static final String THREADS_ENDPOINT = "/content/posts/threads";
	
	private PostsModel() {}

	public static PostsModel getInstance() {
		if (instance == null) {
			instance = new PostsModel();
		}
		return instance;
	}
	
	private void persistThreads(Context context, String channel, JSONArray postsPerThreads) throws JSONException, ParseException {
		PostsDAO postsDAO = PostsDAO.getInstance(context);
		
		String newestThreadUpdated = null;
		JSONObject newestThread = ThreadsDAO.getInstance(context).getNewest(channel);
		if (newestThread != null) {
			newestThreadUpdated = newestThread.optString("updated");
		}
		
		for (int i = 0; i < postsPerThreads.length(); i++) {
			JSONObject thread = postsPerThreads.optJSONObject(i);
			String threadUpdated = thread.optString("updated");
			if (newestThreadUpdated != null && 
					TimeUtils.after(threadUpdated, newestThreadUpdated)) {
				continue;
			}
			String threadId = thread.optString("id");
			updateThreadTimestamp(context, channel, threadId, threadUpdated);
			JSONArray items = thread.optJSONArray("items");
			for (int j = 0; j < items.length(); j++) {
				JSONObject item = items.optJSONObject(j);
				normalize(item);
				item.put("threadId", threadId);
				if (postsDAO.get(channel, item.optString("id")) == null) {
					postsDAO.insert(channel, item);
				}
			}
		}
	}
	
	public void persistSinglePost(Context context, String channel,
			JSONObject post) throws JSONException, ParseException {
		PostsDAO postsDAO = PostsDAO.getInstance(context);
		String updated = post.optString("updated");
		String threadId = post.has("replyTo") ? post.optString("replyTo")
				: post.optString("id");
		JSONObject thread = ThreadsDAO.getInstance(context).get(threadId);
		if (thread != null) {
			String threadUpdated = thread.optString("updated");
			if (TimeUtils.after(threadUpdated, updated)) {
				updateThreadTimestamp(context, channel, threadId, updated);
			}
		} else {
			updateThreadTimestamp(context, channel, threadId, updated);
		}

		normalize(post);
		post.put("threadId", threadId);
		if (postsDAO.get(channel, post.optString("id")) == null) {
			postsDAO.insert(channel, post);
		}
	}

	private void updateThreadTimestamp(Context context, String channel, 
			String threadId, String threadUpdated) throws JSONException {
		JSONObject thread = new JSONObject();
		thread.put("id", threadId);
		thread.put("channel", channel);
		thread.put("updated", threadUpdated);
		
		ThreadsDAO dao = ThreadsDAO.getInstance(context);
		JSONObject existingThread = dao.get(threadId, channel);
		if (existingThread == null) {
			dao.insert(threadId, thread);
		} else {
			dao.update(threadId, thread);
		}
	}

	private void normalize(JSONObject item) {
		String author = item.optString("author");
		if (author.contains("acct:")) {
			String[] split = author.split(":");
			author = split[1];
			try {
				item.put("author", author);
			} catch (JSONException e) {}
		}
	}
	
	@Override
	public JSONArray getFromCache(Context context, String... p) {
		String channelJid = p[0];
		String after = null;
		if (p.length > 1) {
			after = p[1];
		}
		final PostsDAO postsDAO = PostsDAO.getInstance(context);
		try {
			return postsDAO.get(channelJid, after, LOCAL_PAGE_SIZE);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	private void fetchPosts(final Context context, final String channelJid, 
			final ModelCallback<Void> callback, String after, String before) {
		BuddycloudHTTPHelper.getArray(postsUrl(context, channelJid, after), context,
				new ModelCallback<JSONArray>() {

			@Override
			public void success(final JSONArray response) {
				new AsyncTask<Void, Void, Exception>() {

					@Override
					protected Exception doInBackground(Void... params) {
						try {
							persistThreads(context, channelJid, response);
							return null;
						} catch (Exception e) {
							return e;
						}
					}
					
					@Override
					protected void onPostExecute(Exception e) {
						if (e != null) {
							error(e);
						} else {
							callback.success(null);
						}
					}
					
				}.execute();
			}

			@Override
			public void error(Throwable throwable) {
				callback.error(throwable);
			}
		});
	}
	
	private String postsUrl(Context context, String channel, String after) {
		String apiAddress = Preferences.getPreference(context, Preferences.API_ADDRESS);
		String postsURL = apiAddress + "/" + channel + THREADS_ENDPOINT + "?max=" + REMOTE_PAGE_SIZE;
		if (after != null) {
			postsURL += "&after=" + after;
		}
		return postsURL;
	}
	
	private String postsUrl(Context context, String channel) {
		String apiAddress = Preferences.getPreference(context, Preferences.API_ADDRESS);
		return apiAddress + "/" + channel + POSTS_ENDPOINT;
	}
	
	private String postUrl(Context context, String channel, String postId) {
		String apiAddress = Preferences.getPreference(context, Preferences.API_ADDRESS);
		return apiAddress + "/" + channel + POSTS_ENDPOINT + "/" + postId;
	}
	
	public synchronized void savePendingPosts(final Context context) {
		Semaphore semaphore = new Semaphore(0);
		int permits = 0;
		
		Map<String, JSONArray> pending = PostsDAO.getInstance(context).getPending();
		for (Entry<String, JSONArray> postsPerChannel : pending.entrySet()) {
			String channelJid = postsPerChannel.getKey();
			JSONArray posts = postsPerChannel.getValue();
			for (int i = 0; i < posts.length(); i++) {
				JSONObject post = posts.optJSONObject(i);
				permits++;
				savePendingPost(context, channelJid, post, semaphore);
			}
		}
		
		try {
			semaphore.acquire(permits);
		} catch (InterruptedException e) {}
	}

	private void savePendingPost(final Context context, final String channelJid,
			final JSONObject post, final Semaphore semaphore) {
		try {
			JSONObject tempPost = new JSONObject(post, new String[] {"content", "replyTo", "media" });
			StringEntity requestEntity = new StringEntity(tempPost.toString(), "UTF-8");
			requestEntity.setContentType("application/json");

			BuddycloudHTTPHelper.post(postsUrl(context, channelJid), true, false, requestEntity, context,
				new ModelCallback<JSONObject>() {
					@Override
					public void success(JSONObject response) {
						String postId = post.optString("id");
						PostsDAO.getInstance(context).delete(channelJid, postId);
						notifyDeleted(channelJid, postId, post.optString("replyTo", null));
						notifyChanged();
						semaphore.release();
					}

					@Override
					public void error(Throwable throwable) {
						semaphore.release();
					}
				});
		} catch (Exception e) {
			semaphore.release();
		}
	}
	
	@Override
	public void save(final Context context, JSONObject object,
			final ModelCallback<JSONObject> callback, String... p) {
		if (p == null || p.length < 1) {
			return;
		}
		
		try {
			Logger.debug(TAG, object.toString());
			StringEntity requestEntity = new StringEntity(object.toString(), "UTF-8");
			requestEntity.setContentType("application/json");
			
			String author = (String) Preferences.getPreference(context, Preferences.MY_CHANNEL_JID);
			final String channelJid = p[0];
			
			final String tempItemId = UUID.randomUUID().toString();
			final JSONObject tempObject = new JSONObject(object, new String[]{"content", "replyTo", "media"});
			tempObject.put("id", tempItemId);
			tempObject.put("updated", TimeUtils.formatISO(new Date()));
			tempObject.put("threadUpdated", TimeUtils.formatISO(new Date()));
			tempObject.put("threadId", tempObject.has("replyTo") ? 
					tempObject.optString("replyTo") : tempItemId);
			tempObject.put("author", author);
			tempObject.put("channel", channelJid);

			final PostsDAO postsDAO = PostsDAO.getInstance(context);
			postsDAO.insert(channelJid, tempObject);
			notifyAdded(channelJid, tempObject);
			
			BuddycloudHTTPHelper.post(postsUrl(context, channelJid), true, false, requestEntity, context, 
					new ModelCallback<JSONObject>() {
				
				@Override
				public void success(JSONObject response) {
					postsDAO.delete(channelJid, tempItemId);
					notifyDeleted(channelJid, tempItemId, 
							tempObject.optString("replyTo", null));
					callback.success(response);
					sync(context);
				}

				@Override
				public void error(Throwable throwable) {
					callback.error(throwable);
				}
			});
			
		} catch (UnsupportedEncodingException e) {
			callback.error(e);
		} catch (JSONException e) {
			callback.error(e);
		}
	}

	protected void sync(final Context context) {
		final SyncModel syncModel = SyncModel.getInstance();
		SyncModel.getInstance().syncNoSummary(context, new ModelCallbackImpl<Void>(){
			@Override
			public void success(Void response) {
				syncModel.fill(context, new ModelCallbackImpl<Void>());
			}
			
			@Override
			public void error(Throwable throwable) {
				success(null);
			}
		});
	}
	
	@Override
	public void getFromServer(Context context, ModelCallback<JSONArray> callback,
			String... p) {
		// TODO Auto-generated method stub
		
	}

	public void fillMore(Context context, ModelCallback<Void> callback, String... p) {
		String channelJid = p[0];
		String oldestPostId = p[1];
		fetchPosts(context, channelJid, callback, oldestPostId, null);
	}
	
	@Override
	public void fill(Context context, ModelCallback<Void> callback, String... p) {
		String channelJid = p[0];
		fetchPosts(context, channelJid, callback, null, null);
	}

	public static boolean isPending(JSONObject post) {
		String published = post.optString("published", null);
		return published == null || published.length() == 0;
	}
	
	@Override
	public void delete(final Context context, final ModelCallback<Void> callback, String... p) {
		final String channelJid = p[0];
		final String itemId = p[1];
		
		final JSONObject oldPost = PostsDAO.getInstance(context).get(channelJid, itemId);
		if (oldPost != null && isPending(oldPost)) {
			PostsDAO.getInstance(context).delete(channelJid, itemId);
			notifyDeleted(channelJid, itemId, oldPost.optString("replyTo", null));
			callback.success(null);
			return;
		}
		
		String url = postUrl(context, channelJid, itemId);
		BuddycloudHTTPHelper.delete(url, true, false, context, 
				new ModelCallback<JSONObject>() {
			@Override
			public void success(JSONObject response) {
				if (oldPost != null) {
					PostsDAO.getInstance(context).delete(channelJid, itemId);
					notifyDeleted(channelJid, itemId, oldPost.optString("replyTo", null));
				}
				callback.success(null);
			}
			
			@Override
			public void error(Throwable throwable) {
				callback.error(throwable);
			}
		});
	}
}