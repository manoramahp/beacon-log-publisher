package org.wso2.beaconlogproducer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
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
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BeaconReceiverActivity extends Activity implements BeaconConsumer, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {
    protected static final String TAG = "MonitoringActivity";
    private BeaconManager beaconManager;
    private Queue<BeaconDataRecord> queue;
    private LocationManager locationManager;
    private ScheduledExecutorService fileWriteScheduler, emailScheduler;

    private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 1000;
    private Location mLastLocation;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private boolean mRequestingLocationUpdates = false;
    private String deviceId;

    private static int UPDATE_INTERVAL = 10000;
    private static int FASTEST_INTERVAL = 5000;
    private static int DISPLACEMENT = 10;

    TextView senderEmailText;
    TextView senderPasswordText;
    TextView recipientEmailText;

    String senderEmail = "beaconlog.publisher@gmail.com";;
    String senderPassword = "xxxxxx";;
    String recepientEmail= "beaconlog.publisher@gmail.com";;

    Context context = this;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_beacon_receiver);
            initUIFields();
            queue = new ConcurrentLinkedQueue<BeaconDataRecord>();
            deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

            // location
            locationManager = (LocationManager) context.getSystemService(LOCATION_SERVICE);
            checkPlayServices();
            buildGoogleApiClient();
            createLocationRequest();
            mRequestingLocationUpdates = true;

        } catch (Throwable e) {
            Log.e("ERROR On create", e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /**
     * Start collecting location and beacon data
     * @param view
     */
    public void startCollectingData(View view) {

        // beacon data
        beaconManager = BeaconManager.getInstanceForApplication(this);
        beaconManager.getBeaconParsers().add(new BeaconParser().
                setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));
        beaconManager.bind(this);

        // write to file - every 5 seconds
        fileWriteScheduler = Executors.newSingleThreadScheduledExecutor();
        fileWriteScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    publishBeaconData();
                } catch (Throwable e) {
                    Log.e("Error : log beacon data", e.getMessage());
                }
            }
        }, 0, 5, TimeUnit.SECONDS);

        // send email attachment - every hour
        emailScheduler = Executors.newSingleThreadScheduledExecutor();
        emailScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    sendEmailAttachment();
                } catch (Throwable e) {
                    Log.e("Error : send email", e.getMessage());
                }
            }
        }, 0, 1, TimeUnit.HOURS);

    }

    /**
     * Stop collecting location and beacon data
     * @param view
     */
    public void stopCollectingData(View view) {
        beaconManager.unbind(this);
        fileWriteScheduler.shutdown();
        emailScheduler.shutdown();
    }

    /**
     * Initialize the UI fields
     */
    private void initUIFields() {
        senderEmailText = (TextView)findViewById(R.id.enter_sender_email_txt);
        senderPasswordText = (TextView)findViewById(R.id.enter_sender_password_txt);
        recipientEmailText = (TextView)findViewById(R.id.enter_recipient_email_txt);
        senderEmailText.setText(senderEmail);
        senderPasswordText.setText(senderPassword);
        recipientEmailText.setText(recepientEmail);
    }

    /**
     * Get the email addresses for the sender and receiver from the user entered values
     * @param view
     */
    public void saveEmailAddress(View view) {
        senderEmail = senderEmailText.getText().toString();
        senderPassword = senderPasswordText.getText().toString();
        recepientEmail = recipientEmailText.getText().toString();
    }

    /**
     * Send the mail containing the attachments
     */
    private void sendEmailAttachment() {
        try {
            GMailSender sender = new GMailSender(senderEmail, senderPassword);
            String subject = "Beacon/location Logs";
            String body = "Please find the attached beacon and location log files. \n";
            sender.sendMail(subject, body, senderEmail, recepientEmail, deviceId);
        } catch (Exception e) {
            Log.e("Error sending mail", e.getMessage());
        }
    }

    @Override
    public void onBeaconServiceConnect() {
        try {
            beaconManager.setBackgroundScanPeriod(1000l);
            beaconManager.setBackgroundBetweenScanPeriod(1000l);
            beaconManager.updateScanPeriods();
        } catch (RemoteException e) {
            Log.e("Error : update scan", e.getMessage());
        }
        beaconManager.setRangeNotifier(new RangeNotifier() {
            @Override
            public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
                try {
                    if (beacons.size() > 0) {
                        Log.i(TAG, "Reading from beacon");

                        Iterator<Beacon> beaconIterator = beacons.iterator();
                        while (beaconIterator.hasNext()) {
                            Beacon beacon = beaconIterator.next();
                            BeaconDataRecord beaconDataRecord = new BeaconDataRecord();
                            beaconDataRecord.setUuid(String.valueOf(beacon.getId1()));
                            beaconDataRecord.setMajor(String.valueOf(beacon.getId2()));
                            beaconDataRecord.setMinor(String.valueOf(beacon.getId3()));
                            beaconDataRecord.setDistance(String.valueOf(beacon.getDistance()));
                            beaconDataRecord.setRssi(String.valueOf(beacon.getRssi()));
                            queue.add(beaconDataRecord);
                        }
                    }
                } catch (Throwable e) {
                    Log.e("Unexpected error", e.getMessage());
                }
            }
        });

        try {
            beaconManager.startRangingBeaconsInRegion(new Region("myRangingUniqueId", null, null, null));
        } catch (RemoteException e) {
            Log.e("Error : beacon ranging", e.getMessage());
        }
    }

    /**
     * Publish the received beacon data to the local file
     */
    private void publishBeaconData() {
        BufferedWriter buf;
        try {
            String logString = "";
            createLocationRequest();
            showLocation();
            JSONObject jsonObj = new JSONObject();

            if (mLastLocation != null) {
                Double latitude = mLastLocation.getLatitude();
                Double longitude = mLastLocation.getLongitude();

                jsonObj.put("timestamp", System.currentTimeMillis());
                jsonObj.put("latitude", latitude);
                jsonObj.put("longitude", longitude);
            }
            BeaconDataRecord record = queue.poll();
            if (record != null) {
                jsonObj.put("beaconUuid", record.getUuid());
                jsonObj.put("beaconMajor", record.getMajor());
                jsonObj.put("beaconMinor", record.getMinor());
                jsonObj.put("distance", record.getDistance());
                jsonObj.put("rssi", record.getRssi());
            }
            logString = jsonObj.toString();
            while (!queue.isEmpty()) {
                BeaconDataRecord dataRecord = queue.poll();
                jsonObj.put("beaconUuid", dataRecord.getUuid());
                jsonObj.put("beaconMajor", dataRecord.getMajor());
                jsonObj.put("beaconMinor", dataRecord.getMinor());
                jsonObj.put("distance", dataRecord.getDistance());
                jsonObj.put("rssi", dataRecord.getRssi());
                logString += "\n" + jsonObj.toString();
            }

            SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy-HH");
            String fileName = "beaconlog-"+ deviceId + "-" + format.format(new Date()) + ".log";
            File logFile = new File(Environment.getExternalStorageDirectory(), fileName);
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            //BufferedWriter for performance, true to set append to file flag
            buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(logString);
            buf.newLine();
            buf.flush();
            buf.close();
        } catch (Exception e) {
            Log.e("Error : writing logs", e.getMessage());
        }
    }

    // Location
    private void showLocation() {
        mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        if (mLastLocation != null) {
            Double latitude = mLastLocation.getLatitude();
            Double longitude = mLastLocation.getLongitude();
            Log.d("location ", "Longitude : " + longitude + " , Latitude :" + latitude);
        } else {
            Log.d("ERROR :", "ERROR");
        }
    }

    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApiIfAvailable(LocationServices.API).build();
    }

    private boolean checkPlayServices() {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
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
    protected void onStart() {
        super.onStart();
        if (mGoogleApiClient != null) {
            mGoogleApiClient.connect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPlayServices();
    }

    /**
     * Create location request
     */
    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(UPDATE_INTERVAL);
        mLocationRequest.setFastestInterval(FASTEST_INTERVAL);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setSmallestDisplacement(DISPLACEMENT);
    }

    /**
     * Location api configurations
     */
    protected void startLocationUpdates() {
        Intent alarm = new Intent(BeaconReceiverActivity.this, BeaconReceiverActivity.class);
        PendingIntent recurringAlarm =
                PendingIntent.getBroadcast(context,
                        1,
                        alarm,
                        PendingIntent.FLAG_CANCEL_CURRENT);
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, recurringAlarm);
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