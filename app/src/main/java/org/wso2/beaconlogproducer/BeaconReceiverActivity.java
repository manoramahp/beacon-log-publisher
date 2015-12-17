package org.wso2.beaconlogproducer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.MonitorNotifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BeaconReceiverActivity extends Activity implements BeaconConsumer, GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener, LocationListener {
    protected static final String TAG = "MonitoringActivity";
    private BeaconManager beaconManager;
    private Queue<BeaconDataRecord> queue;
    private LocationManager locationManager;
    private double latitude, longitude;

    // Location
   // private static final String TAG2=BeaconReceiverActivity.class.getSimpleName();
    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 1000;
    private Location mLastLocation ;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private boolean mRequestingLocationUpdates = false;

    private static int UPDATE_INTERVAL = 10000;
    private static int FASTEST_INTERVAL = 5000;
    private static int DISPLACEMENT = 10;

    Context context=this;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beacon_receiver);
        queue = new ConcurrentLinkedQueue<BeaconDataRecord>();

        locationManager=(LocationManager)context.getSystemService(LOCATION_SERVICE);
        checkPlayServices();
        buildGoogleApiClient();
        createLocationRequest();
        mRequestingLocationUpdates = true;

        beaconManager = BeaconManager.getInstanceForApplication(this);
//        beaconManager.setForegroundScanPeriod(5000l);
        // To detect proprietary beacons, you must add a line like below corresponding to your beacon
        // type.  Do a web search for "setBeaconLayout" to get the proper expression.
//         beaconManager.getBeaconParsers().add(new BeaconParser().
//                setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));

//        beaconManager.getBeaconParsers().add(new BeaconParser().
//                setBeaconLayout("m:2-3=0203,i:14-19l,d:10-13,p:9-9"));

//        beaconManager.getBeaconParsers().add(new BeaconParser().
//                setBeaconLayout("m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24"));

//        beaconManager.getBeaconParsers().add(new BeaconParser().
//                setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));

//        beaconManager.getBeaconParsers().add(new BeaconParser(). setBeaconLayout("m:0-3=4c000215,i:4-19,i:20-21,i:22-23,p:24-24"));

        beaconManager.getBeaconParsers().add(new BeaconParser().
                setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));

        beaconManager.setBackgroundScanPeriod(1000l);
        beaconManager.setBackgroundBetweenScanPeriod(1000l);
        try {
            beaconManager.updateScanPeriods();
        } catch (RemoteException e) {
            Log.e("Error : beacon manager", e.getMessage());
        }

        beaconManager.bind(this);

//        new Thread() {
//            @Override
//            public void run() {
//                publishBeaconData();
//            }
//        }.start();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                publishBeaconData();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        beaconManager.unbind(this);
    }
    @Override
    public void onBeaconServiceConnect() {
//        beaconManager.setBackgroundMode(true);
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

        try {
            beaconManager.startMonitoringBeaconsInRegion(new Region("myMonitoringUniqueId", null, null, null));
//            beaconManager.startMonitoringBeaconsInRegion(new Region("myMonitoringUniqueId", Identifier.parse("0"), Identifier.parse("1"), Identifier.parse("2")));
        } catch (RemoteException e) {
            Log.e("Error monitoring", e.getMessage());
        }

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
            beaconManager.startRangingBeaconsInRegion(new Region("myRangingUniqueId", null, null, null));
//            beaconManager.startRangingBeaconsInRegion(new Region("myRangingUniqueId", Identifier.parse("MY_UUID"), Identifier.parse("1"), Identifier.parse("2")));
        } catch (RemoteException e) {
            Log.e("Error : beacon ranging", e.getMessage());
        }
    }

    private void publishBeaconData() {
        try {
            String logString = "";
//            while (true) {
                createLocationRequest();
                showLocation();
                Double latitude = mLastLocation.getLatitude();
                Double longitude = mLastLocation.getLongitude();

                JSONObject jsonObj = new JSONObject();
//                jsonObj.put("beaconUuid", "d3fbdbe3-e95b-41a4-8b73-8a02feb257ba");
//                jsonObj.put("beaconMajor", "1");
//                jsonObj.put("beaconMinor", "2");
                jsonObj.put("timestamp", System.currentTimeMillis());
                jsonObj.put("latitude", latitude);
                jsonObj.put("longitude", longitude);

                BeaconDataRecord record = queue.poll();
                if (record != null) {
                    jsonObj.put("beaconUuid", record.getUuid());
                    jsonObj.put("beaconMajor", record.getMajor());
                    jsonObj.put("beaconMinor", record.getMinor());
                }
                logString = jsonObj.toString();

                File logFile = new File(Environment.getExternalStorageDirectory(), "beaconlog");
                if (!logFile.exists()) {
                    logFile.createNewFile();
                }
                //BufferedWriter for performance, true to set append to file flag
                BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
                buf.append(logString);
                buf.newLine();
                buf.flush();
                buf.close();
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED,
                        Uri.parse("file://" + Environment.getExternalStorageDirectory())));
//                Thread.sleep(1000);
//            }

        } catch (Exception e) {
            Log.e("Error : writing logs", e.getMessage());
        }
    }

    // Location
    private void showLocation(){
        mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        if(mLastLocation!=null){

            Double latitude = mLastLocation.getLatitude();
            Double longitude = mLastLocation.getLongitude();
            Log.d("location ","Longitude : "+longitude+" , Latitude :"+latitude);
        }
        else{
            Toast.makeText(getApplicationContext(),"Last Location is null",Toast.LENGTH_LONG).show();
            Log.d("ERROR :","ERROR");
        }
    }

    protected synchronized void buildGoogleApiClient(){
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API).build();
    }

    private boolean checkPlayServices(){
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if(resultCode!= ConnectionResult.SUCCESS) {
            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                GooglePlayServicesUtil.getErrorDialog(resultCode, this, PLAY_SERVICES_RESOLUTION_REQUEST).show();
            } else {
                Toast.makeText(getApplicationContext(), "This device is not supported.", Toast.LENGTH_LONG).show();
                finish();
            }
            return false;
        }
        return true;
    }

    @Override
    public void onConnected(Bundle bundle) {
        showLocation();
        startLocationUpdates();
    }

    @Override
    public void onConnectionSuspended(int i) {
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.i(TAG, "Connection failed : ConnectionResult.getErrorCode() = " + connectionResult.getErrorCode());
    }

    @Override
    protected void onStart(){
        super.onStart();
        if(mGoogleApiClient != null){
            mGoogleApiClient.connect();
        }
    }

    @Override
    protected void onResume(){
        super.onResume();;
        checkPlayServices();
    }

    private void togglePeriodicLocationUpdates(){
        if(!mRequestingLocationUpdates){
            startLocationUpdates();
            Log.d(TAG,"Periodic locaion updates started!");
        }
    }

    protected void createLocationRequest(){
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(UPDATE_INTERVAL);
        mLocationRequest.setFastestInterval(FASTEST_INTERVAL);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setSmallestDisplacement(DISPLACEMENT);
    }

    protected void startLocationUpdates(){
        Intent alarm = new Intent(BeaconReceiverActivity.this, BeaconReceiverActivity.class);
        PendingIntent recurringAlarm =
                PendingIntent.getBroadcast(context,
                        1,
                        alarm,
                        PendingIntent.FLAG_CANCEL_CURRENT);
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient,mLocationRequest, recurringAlarm);
    }

    @Override
    public void onLocationChanged(Location location) {
        mLastLocation = location;
        showLocation();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {

    }

    @Override
    public void onProviderEnabled(String provider) {

    }

    @Override
    public void onProviderDisabled(String provider) {

    }
}