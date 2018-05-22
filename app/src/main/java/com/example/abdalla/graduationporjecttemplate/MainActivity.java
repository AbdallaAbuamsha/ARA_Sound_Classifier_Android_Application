package com.example.abdalla.graduationporjecttemplate;


import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import com.newventuresoftware.waveform.WaveformView;

public class MainActivity extends AppCompatActivity {

    /****************** UI Variables ********************************/
    private WaveformView mRealtimeWaveformView;
    MainActivityController mainActivityController;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mRealtimeWaveformView = (WaveformView) findViewById(R.id.waveformView);
        mainActivityController = new MainActivityController(this, new AudioDataReceivedListener() {
            @Override
            public void onAudioDataReceived(short[] data) {
                mRealtimeWaveformView.setSamples(data);
            }
        });

//        short[] samples = null;
//        try {
//            samples = getAudioSample();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
    }

//    private short[] getAudioSample() throws IOException{
//        InputStream is = getResources().openRawResource(R.raw.jinglebells);
//        byte[] data;
//        try {
//            data = IOUtils.toByteArray(is);
//        } finally {
//            if (is != null) {
//                is.close();
//            }
//        }
//
//        ShortBuffer sb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
//        short[] samples = new short[sb.limit()];
//        sb.get(samples);
//        return samples;
//    }



}
