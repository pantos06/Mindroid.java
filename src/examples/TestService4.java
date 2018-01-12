package examples;

import java.util.Arrays;
import mindroid.app.Service;
import mindroid.content.Intent;
import mindroid.os.AsyncTask;
import mindroid.os.IBinder;
import mindroid.util.Log;

public class TestService4 extends Service {
	private static final String LOG_TAG = "TestService4";

	public void onCreate() {
	    Log.i(LOG_TAG, "onCreate");
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
	    AsyncTask<Integer, Integer, Integer> asyncTask = new AsyncTask<Integer, Integer, Integer>() {
	        @Override
	        protected void onPreExecute() {
	            Log.i(LOG_TAG, "onPreExecute");
	        }

            @Override
            protected Integer doInBackground(Integer... params) {
                Log.i(LOG_TAG, "doInBackground: " + Arrays.toString(params));
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        publishProgress(params[i]);
                    }
                }
                return 42;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                Log.i(LOG_TAG, "onProgressUpdate: " + Arrays.toString(values));
            }

            @Override
            protected void onPostExecute(Integer result) {
                Log.i(LOG_TAG, "onPostExecute: " + result);
            }
	    };
	    asyncTask.execute(1, 2, 3);
	
		return 0;
	}

	public IBinder onBind(Intent intent) {
		return null;
	}

	public void onDestroy() {
	    Log.i(LOG_TAG, "onDestroy");
	}
}
