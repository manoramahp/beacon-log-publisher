package org.wso2.beaconlogproducer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.util.Log;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.MonitorNotifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HTTP;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BeaconReceiverActivity extends Activity implements BeaconConsumer {
    protected static final String TAG = "MonitoringActivity";
    private BeaconManager beaconManager;
    private Queue<BeaconDataRecord> queue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beacon_receiver);
        beaconManager = BeaconManager.getInstanceForApplication(this);
        // To detect proprietary beacons, you must add a line like below corresponding to your beacon
        // type.  Do a web search for "setBeaconLayout" to get the proper expression.
//         beaconManager.getBeaconParsers().add(new BeaconParser().
//                setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));
        beaconManager.bind(this);

        queue = new ConcurrentLinkedQueue<BeaconDataRecord>();
        new Thread() {
            @Override
            public void run() {

                publishBeaconData();
            }
        }.start();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        beaconManager.unbind(this);
    }
    @Override
    public void onBeaconServiceConnect() {
        beaconManager.setMonitorNotifier(new MonitorNotifier() {
            @Override
            public void didEnterRegion(org.altbeacon.beacon.Region region) {
                Log.i(TAG, "Entered beacon region");
            }

            @Override
            public void didExitRegion(org.altbeacon.beacon.Region region) {
                Log.i(TAG, "Exited beacon region");
            }

            @Override
            public void didDetermineStateForRegion(int i, org.altbeacon.beacon.Region region) {
                Log.i(TAG, "Switched from seeing/not seeing beacons: "+i);
            }
        });

        beaconManager.setRangeNotifier(new RangeNotifier() {
            @Override
            public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
                if (beacons.size() > 0) {
                    Log.i(TAG, "Reading from beacon");

                    while (beacons.iterator().hasNext()) {
                        Beacon beacon = beacons.iterator().next();
                        BeaconDataRecord beaconDataRecord = new BeaconDataRecord();
                        beaconDataRecord.setUuid(String.valueOf(beacon.getId1()));
                        beaconDataRecord.setMajor(String.valueOf(beacon.getId2()));
                        beaconDataRecord.setMinor(String.valueOf(beacon.getId3()));
                        queue.add(beaconDataRecord);
                    }
                }
            }
        });

        try {
            beaconManager.startMonitoringBeaconsInRegion(new Region("myMonitoringUniqueId", null, null, null));
        } catch (RemoteException e) {    }
    }


    private void publishBeaconData() {

        HttpClient httpClient = new DefaultHttpClient();

        try {
            // TODO add the ESB server url here
//            HttpPost httpPost = new HttpPost("http://10.100.7.15:8282/test-beacon");

            File filesDir = getFilesDir();
            String logsBatch = "";
            int count = 100;

            while (true) {
                JSONObject jsonObj = new JSONObject();
                jsonObj.put("beaconUuid", "d3fbdbe3-e95b-41a4-8b73-8a02feb257ba");
                jsonObj.put("beaconMajor", "1");
                jsonObj.put("beaconMinor", "2");

                BeaconDataRecord record = queue.poll();
                if(record != null) {
                    jsonObj.put("beaconUuid", record.getUuid());
                    jsonObj.put("beaconMajor", record.getMajor());
                    jsonObj.put("beaconMinor", record.getMinor());
                }

//                StringEntity entity = new StringEntity(jsonObj.toString(), HTTP.UTF_8);
//                entity.setContentType("application/json");
//                httpPost.setEntity(entity);
//                HttpResponse response = httpClient.execute(httpPost);
//                response.getEntity().consumeContent();

                while (count > 0) {
                    logsBatch += jsonObj.toString() + "\n";
                    count--;
                }
                //Environment.getExternalStorageDirectory()
                if (count == 0) {
//                    File logfile = new File(Environment.getExternalStorageDirectory(), "beaconlog3");
//                    logfile.createNewFile();
//                    FileOutputStream fileOutputStream = openFileOutput(logfile.getName(), MODE_APPEND);
//                    OutputStreamWriter osw = new OutputStreamWriter(fileOutputStream);
//                    osw.write(logsBatch);
//                    osw.flush();
//                    osw.close();
//                    count = 100;
//
//                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED,
//                            Uri.parse("file://" + Environment.getExternalStorageDirectory())));

                    File logFile = new File(Environment.getExternalStorageDirectory(), "beaconlog");
                    if (!logFile.exists()) {
                            logFile.createNewFile();

                    }
                    //BufferedWriter for performance, true to set append to file flag
                    BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
                    buf.append(logsBatch);
                    buf.newLine();
                    buf.flush();
                    buf.close();
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED,
                            Uri.parse("file://" + Environment.getExternalStorageDirectory())));
                }
            }

        } catch (Exception e) {
            Log.e("Error : writing logs", e.getMessage());
        }
    }
}