package com.example.abdalla.graduationporjecttemplate;

import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.locks.ReentrantLock;


public class MainActivityController {
    private AppCompatActivity mainActivity;

    private static final String LOG_TAG = "MYLOG";
    private static int RESAULT_NUMBER = 0;
    /****************** Recording Parameters ********************************/
    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLE_DURATION_MS = 3000;
    private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
    private short[] recordingBuffer = new short[RECORDING_LENGTH];

    /****************** MultiThreading Parameters ********************************/
    private Thread recordingThread;
    private Thread recognitionThread;
    private boolean shouldContinue = true;
    private boolean shouldContinueRecognition = true;
    private final ReentrantLock recordingBufferLock = new ReentrantLock();

    private int recordingOffset = 0;


    private static final int REQUEST_RECORD_AUDIO = 13;

    private AudioDataReceivedListener mListener;
    MainActivityController(AppCompatActivity _mainActivity, AudioDataReceivedListener listener)
    {
        this.mainActivity = _mainActivity;
        this.mListener = listener;
        requestMicrophonePermission();
        startRecording();
        startRecognition();
    }

    private void requestMicrophonePermission() {
        ActivityCompat.requestPermissions(mainActivity, new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
    }

    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(mainActivity, "i have premission yeaaah", Toast.LENGTH_SHORT).show();
            startRecording();
            startRecognition();
        }
    }

    private synchronized void startRecording() {
        if (recordingThread != null) {
            return;
        }
        shouldContinue = true;
        Toast.makeText(mainActivity, "Start Recording", Toast.LENGTH_SHORT).show();
        recordingThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                record();
                            }
                        });
        recordingThread.start();
    }

    private void record() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // Estimate the buffer size we'll need for this device.
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }

        short[] audioBuffer = new short[bufferSize / 2];

        final AudioRecord record =
                new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        Log.v(LOG_TAG, "Start recording");

        // Loop, gathering audio data and copying it to a round-robin buffer.
        long shortsRead = 0;
        while (shouldContinue) {
            int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberRead;
            mListener.onAudioDataReceived(audioBuffer);
            int maxLength = recordingBuffer.length;
            int newRecordingOffset = recordingOffset + numberRead;
            int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
            int firstCopyLength = numberRead - secondCopyLength;
            // We store off all the data for the recognition thread to access. The ML
            // thread will copy out of this buffer into its own, while holding the
            // lock, so this should be thread safe.
            recordingBufferLock.lock();
            try {
                System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength);
                System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength);
                recordingOffset = newRecordingOffset % maxLength;
            } finally {
                recordingBufferLock.unlock();
            }
        }
        record.stop();
        record.release();
    }

    public synchronized void stopRecording() {
        if (recordingThread == null) {
            return;
        }
        shouldContinue = false;
        recordingThread = null;
    }

    private synchronized void startRecognition() {
        if (recognitionThread != null) {
            return;
        }
        shouldContinueRecognition = true;
        recognitionThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                recognize();
                            }
                        });
        recognitionThread.start();
    }

    public synchronized void stopRecognition() {
        if (recognitionThread == null) {
            return;
        }
        shouldContinueRecognition = false;
        recognitionThread = null;
    }


    private void recognize() {
        Log.v(LOG_TAG, "Start recognition");

        short[] inputBuffer = new short[RECORDING_LENGTH];
        float[] floatInputBuffer = new float[RECORDING_LENGTH];
        int[] sampleRateList = new int[]{SAMPLE_RATE};

        // Loop, grabbing recorded data and running the recognition model on it.

        while (shouldContinueRecognition) {
            // The recording thread places data in this round-robin buffer, so lock to
            // make sure there's no writing happening and then copy it to our own
            // local version.
            recordingBufferLock.lock();
            try {
                int maxLength = recordingBuffer.length;
                int firstCopyLength = maxLength - recordingOffset;
                int secondCopyLength = recordingOffset;
                System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength);
                System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength);
            } finally {
                recordingBufferLock.unlock();
            }

            // We need to feed in float values between -1.0f and 1.0f, so divide the
            // signed 16-bit inputs.
            for (int i = 0; i < RECORDING_LENGTH; ++i) {
                floatInputBuffer[i] = inputBuffer[i] / 32767.0f;
            }


            RequestQueue requestQueue = Volley.newRequestQueue(mainActivity);
            String URL = "https://ara-sound-app.herokuapp.com/api";
            final JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("sound", new JSONArray(floatInputBuffer));

                Log.v(LOG_TAG, jsonBody.toString());
            } catch (JSONException e) {
                Log.v(LOG_TAG, "can't convert array");
                e.printStackTrace();

            }
            final String requestBody = jsonBody.toString();

            StringRequest stringRequest = new StringRequest(Request.Method.POST, URL,new Response.Listener<String>() {

                @Override
                public void onResponse(String response) {
                    //try {
                    //JSONObject jsonResponse = new JSONObject(response);
                    //Toast.makeText(MainActivity.this, jsonResponse.toString(), Toast.LENGTH_SHORT).show();
                    Log.v(LOG_TAG, response);
                    RESAULT_NUMBER +=1;
                    Toast.makeText(mainActivity, RESAULT_NUMBER + " = "+ response, Toast.LENGTH_SHORT).show();
//                    } catch (JSONException e) {
//                        Log.v(LOG_TAG, "json responce exception");
//                        e.printStackTrace();
//                    }
                }
            }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    //Log.v(LOG_TAG, error.getMessage());
                    Toast.makeText(mainActivity, "ERRPR :"+error.getMessage(), Toast.LENGTH_LONG).show();
                    //if(LoginActivity.this.dialog.isShowing())LoginActivity.this.dialog.dismiss();
                }
            })
            {

                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }

//                @Override
//                protected Map<String, String> getParams() throws AuthFailureError {
//                    Map<String, String> map = new HashMap<String, String>();
//                    map.put("username", username);
//                    map.put("password", password);
//                    return map;
//                }

                @Override
                public byte[] getBody() throws AuthFailureError {
                    try {
                        return requestBody == null ? null : requestBody.getBytes("utf-8");
                    } catch (UnsupportedEncodingException uee) {
                        VolleyLog.wtf("Unsupported Encoding while trying to get the bytes of %s using %s", requestBody, "utf-8");
                        return null;
                    }
                }
            };
//            requestQueue.add(stringRequest);
//            stopRecording();
//            stopRecognition();
        }

        Log.v(LOG_TAG, "End recognition");
    }
}
