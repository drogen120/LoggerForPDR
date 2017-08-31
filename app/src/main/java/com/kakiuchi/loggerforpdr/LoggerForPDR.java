package com.kakiuchi.loggerforpdr;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus.NmeaListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.FloatMath;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.List;

import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.view.SurfaceHolder;
import android.view.SurfaceView;





/*
 * PDR手法の精度検証実験用に、GPS・加速度・磁気の生データを（同期せずに）記録するアプリ。
 * 実験としては、正確な距離を測ったコース上を歩いたり走ったりするものを想定。
 * 0926 NMEAを取得してPDOP, HDOP, VDOPも記録する
 */
public class 0LoggerForPDR extends Activity implements LocationListener, SensorEventListener, OnClickListener, NmeaListener{

	//画面構成部品
	private Spinner modeSpin, placeSpin;
	private EditText nameEdit;
	private TextView gpsStateText, sensorStateText, accRateText,magRateText,gyroRateText, preRateText;
	private Button startstopButton, cancelButton;
	private SurfaceView sView;
	private SurfaceHolder surfaceHolder;

	private SharedPreferences pref; //入力した測定者名を保存、次回起動時にデフォルトで表示

	private LocationManager locManager;
	private boolean isFixed; //GPSの位置情報が安定しているかどうか

	private SensorManager sensorManager;
	private Sensor accelerometer, magnetometer, gyroscope, pressure;

	private PrintWriter gpsWriter, accelWriter, magneWriter, situWriter, gyroWriter, preWriter;
	private File gpsFile, accelFile, magneFile, situFile, gyroFile, audioFile, preFile;

	private MediaRecorder mediarecorder;


	private static final String COMMA = ",";
	private static final String NMEA_GPGSA = "$GPGSA";
    private static final String NMEA_GPGGA = "$GPGGA";
    private static final int GGA_UTC_COLUMN = 1;
	private static final int GSA_PDOP_COLUMN = 15;
	private static final int GSA_HDOP_COLUMN = 16;
	private static final int GSA_VDOP_COLUMN = 17;



	private String nmeaPdop, nmeaHdop, nmeaVdop,nmeaUTC;

	private static final float START_ACCURACY_THRESH = 150f; //この精度でGPSの位置情報が取れたら測定開始できる[m]
	private static final long GPS_INTERVAL = 1000; //GPS測定の最短間隔[ミリ秒]
	private static final int SENSOR_INTERVAL = 1000000/32; //加速度／磁気測定の（最短）間隔[マイクロ秒]
	private long accelRefTime; //加速度サンプリングレート検査用
    private long magRefTime; // used for check the magnitude sensor
    private long gyroRefTime;
	private long preRefTime;
	private int accelCount, magCount, gyroCount, preCount;    //同上

	private   Camera camera;

	private static final float MAGNETIC_MIN = 15f; //磁気センサが狂っているor外乱があることを検出
	private static final float MAGNETIC_MAX = 75f; //するための下限値と上限値[μT]

	private String SDCardPath; //一時ファイルや結果ファイルの保存ディレクトリのパス
	private String startTime;  //測定開始時刻（文字列）
	private long startUnixTime; //測定開始時刻（記録時間計測用）

	private long uptime_nano; //スマホの電源が入ってからの時間。センサ類のtimestampの値が保存される変数。

	private boolean logStarted; //測定中かどうか
	private boolean logCancelled; //キャンセルボタンでアプリが終了したかどうか

	/* アプリ起動時 */
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logger_for_pdr);

        modeSpin = (Spinner)this.findViewById(R.id.spinner1);
        placeSpin = (Spinner)this.findViewById(R.id.spinner2);
        nameEdit = (EditText)this.findViewById(R.id.editText1);
        gpsStateText = (TextView)this.findViewById(R.id.textView5);
        sensorStateText = (TextView)this.findViewById(R.id.textView6);
        accRateText = (TextView)this.findViewById(R.id.ACC_label_content);
        magRateText = (TextView)this.findViewById(R.id.MAGN_label_content);
        gyroRateText = (TextView)this.findViewById(R.id.GYRO_label_content);
        startstopButton = (Button)this.findViewById(R.id.button0);
        startstopButton.setOnClickListener(this);
        cancelButton = (Button)this.findViewById(R.id.button1);
        cancelButton.setOnClickListener(this);
		sView = (SurfaceView) findViewById(R.id.dView);
		sView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		sView.getHolder().setFixedSize(1920, 1080);
		sView.getHolder().setKeepScreenOn(true);



		pref = PreferenceManager.getDefaultSharedPreferences(this);
        nameEdit.setText( pref.getString("name", "") );

        isFixed = false;

        accelRefTime = 0;
        accelCount = 0;
        magCount = 0;
        magRefTime = 0;
		preCount = 0;

        logStarted = false;
        uptime_nano=0;

        nmeaPdop = "";
        nmeaHdop = "";
        nmeaVdop = "";

        gpsWriter = null;
        accelWriter = null;
        magneWriter = null;
        situWriter = null;
		preWriter = null;
        logCancelled = false;

        //GPSの起動
    	locManager = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
    	if(locManager != null){
    		locManager.addNmeaListener(this); //NMEAも取得
	        locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL , 0, this);

	        //GPSがオンになっているか確認、表示
    		if(locManager.isProviderEnabled(LocationManager.GPS_PROVIDER) == false){
    			gpsStateText.setText(R.string.GPS_unavailable);
    		}else{
    			gpsStateText.setText(R.string.GPS_unfix);
    		}
    	}

		//加速度／磁気センサの起動 + gryo
        sensorManager = (SensorManager)this.getSystemService(Context.SENSOR_SERVICE);

        List<Sensor> list = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        if(list.size()>0){
        	accelerometer = list.get(0);
        	sensorManager.registerListener(this, accelerometer, SENSOR_INTERVAL);
        }
        list = sensorManager.getSensorList(Sensor.TYPE_MAGNETIC_FIELD);
        if(list.size()>0){
        	magnetometer = list.get(0);
        	sensorManager.registerListener(this, magnetometer, SENSOR_INTERVAL);
        }
        list = sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (list.size() > 0){
            gyroscope = list.get(0);
            sensorManager.registerListener(this,gyroscope,SENSOR_INTERVAL);
        }
		list = sensorManager.getSensorList(Sensor.TYPE_PRESSURE);
		if (list.size() > 0){
			pressure = list.get(0);
			sensorManager.registerListener(this,pressure,SENSOR_INTERVAL);
		}

        //ストレージのパスを取得
		SDCardPath = Environment.getExternalStorageDirectory().getPath() + "/" + this.getPackageName();
		File dir = new File(SDCardPath);
		if(!dir.exists()){ dir.mkdir(); }
		SDCardPath += "/";

        startstopButton.setVisibility(View.VISIBLE);

		//センサキャリブレーションの指示
		Toast.makeText(this, R.string.please_calibrate, Toast.LENGTH_LONG ).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_logger_for_pdr, menu);
        return true;
    }

    private void createLocalDirectory(){
        this.SDCardPath = this.SDCardPath + getCurrentYYYYMMDDhhmm() + nameEdit.getText().toString();
        File dir = new File(SDCardPath);
        if(!dir.exists()){ dir.mkdir(); }
        SDCardPath += "/";
    }

    /* ボタンが押されたときの動作 */
	public void onClick(View v) {

		if(v==startstopButton){


            //測定開始時
			if(!logStarted){
                createLocalDirectory();
                if(nameEdit.getText().length()==0){
					//測定者名に何も入力されていない場合はエラーを出すだけ
					Toast.makeText(this, R.string.name_is_empty, Toast.LENGTH_SHORT).show();
				}else{
					//現在時刻と選択された運動モードを取得
					startTime = getCurrentYYYYMMDDhhmm();
					String mode = (String)modeSpin.getSelectedItem();

					mediarecorder = new MediaRecorder();
//					camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
//					camera.setDisplayOrientation(90);
//					Camera.Parameters parameters = camera.getParameters();
					CamcorderProfile camProf = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
//					camera.unlock();
					mediarecorder.reset();
//					mediarecorder.setCamera(camera);
					mediarecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
					mediarecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
//					mediarecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
//					mediarecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
//					mediarecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);
					mediarecorder.setOutputFormat(camProf.fileFormat);
					mediarecorder.setVideoFrameRate(camProf.videoFrameRate);
					mediarecorder.setVideoSize(camProf.videoFrameWidth, camProf.videoFrameHeight);
					mediarecorder.setVideoEncoder(camProf.videoCodec);
					mediarecorder.setAudioEncoder(camProf.audioCodec);
					mediarecorder.setOrientationHint(90);
//					mediarecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));
//					mediarecorder.setVideoSize(1920, 1080);
					mediarecorder.setVideoFrameRate(camProf.videoFrameRate);
					mediarecorder.setVideoEncodingBitRate(camProf.videoBitRate);
//					mediarecorder.setVideoEncodingBitRate(5 * 1920 * 1080);
//					mediarecorder.setMaxDuration(3000);


					//記録用のファイルを用意し、1行目を記入
					try{
						gpsFile = new File(SDCardPath  + "GPS" + startTime + ".csv");
						accelFile = new File(SDCardPath + "Accel" + startTime + ".csv");
						magneFile = new File(SDCardPath + "Magne" + startTime + ".csv");
                        gyroFile = new File(SDCardPath  + "Gryo" + startTime +".csv");
						audioFile = new File(SDCardPath  + "Audio" + startTime +".mp4");
						preFile = new File(SDCardPath  + "Pre" + startTime +".csv");

						gpsWriter = new PrintWriter(new BufferedWriter(new FileWriter(gpsFile)));
						accelWriter = new PrintWriter(new BufferedWriter(new FileWriter(accelFile)));
						magneWriter = new PrintWriter(new BufferedWriter(new FileWriter(magneFile)));
                        gyroWriter = new PrintWriter(new BufferedWriter(new FileWriter(gyroFile)));
						preWriter = new PrintWriter(new BufferedWriter(new FileWriter(preFile)));

						audioFile.createNewFile();
						mediarecorder.setOutputFile(audioFile.getAbsolutePath());
						mediarecorder.setPreviewDisplay(sView.getHolder().getSurface());
						mediarecorder.prepare();
						mediarecorder.start();

						gpsWriter.println("UPTIMENANO,GPSTIME,SPEED,LAT,LON,ALT,BEARING,ACCURACY,PDOP,HDOP,VDOP,GPSUTC");
						accelWriter.println("UPTIMENANO,Ax,Ay,Az");
						magneWriter.println("UPTIMENANO,Mx,My,Mz");
                        gyroWriter.println("UPTIMENANO,Gx,Gy,Gz,drift_x,drift_y,drift_z");
						preWriter.println("UPTIMENANO,pressure");


                        //測定状況メモを記入
						situFile = new File(SDCardPath + mode + "Situ" + startTime + ".txt");
						situWriter = new PrintWriter(new BufferedWriter(new FileWriter(situFile)));
						situWriter.println( (String)placeSpin.getSelectedItem() );
						situWriter.println(nameEdit.getText().toString());
					} catch (IOException e){
						e.printStackTrace();
					}
					//画面の変更（「測定中」、状況入力を隠す、ボタンの出現）
					TextView introText,modeText,placeText, nameText;
					introText = (TextView)this.findViewById(R.id.textView1);
					modeText = (TextView)this.findViewById(R.id.textView2);
					placeText = (TextView)this.findViewById(R.id.textView3);
					nameText = (TextView)this.findViewById(R.id.textView4);

					introText.setText(R.string.now_Logging);
					introText.setTextColor(Color.RED);

					modeText.setVisibility(View.INVISIBLE);
					modeSpin.setVisibility(View.INVISIBLE);
					placeText.setVisibility(View.INVISIBLE);
					placeSpin.setVisibility(View.INVISIBLE);
					nameText.setVisibility(View.INVISIBLE);
					nameEdit.setVisibility(View.INVISIBLE);
					gpsStateText.setVisibility(View.INVISIBLE);
					sensorStateText.setVisibility(View.INVISIBLE);

					startstopButton.setText(R.string.finish_logging);
					startstopButton.setTextColor(Color.BLUE);

					cancelButton.setVisibility(View.VISIBLE);

					//測定開始時刻を取得
					startUnixTime = System.currentTimeMillis();

			        //記録開始
					logStarted = true;
				}


			//測定終了時
			}else{
				logStarted = false;
				mediarecorder.stop();
				mediarecorder.release();
				mediarecorder = null;

		    	//記録時間を算出しsituに記入
		    	situWriter.println( "記録時間：" + ( (System.currentTimeMillis()-startUnixTime)/1000 ) + "秒");

		    	//「記録しました」Toast
				Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();

				//アプリの終了
				this.finish();
			}
		}

		//測定キャンセル時
		if(v==cancelButton && logStarted){
			mediarecorder.stop();
			mediarecorder.release();
			mediarecorder = null;
			logStarted = false;
			logCancelled = true;

			//「キャンセルしました」Toast
			Toast.makeText(this, R.string.not_saved, Toast.LENGTH_SHORT).show();

			//アプリの終了
			this.finish();
		}
	}

	/*アプリ終了時*/
	@Override
	public void onDestroy(){
		//GPSとセンサを停止
    	if(locManager != null){
    		locManager.removeUpdates(this);
    		locManager.removeNmeaListener(this);
    	}
    	if(sensorManager != null){
    		sensorManager.unregisterListener(this);
    	}

		//Writerのクローズ
    	if(gpsWriter!=null){ gpsWriter.close(); }
    	if(accelWriter!=null){ accelWriter.close(); }
    	if(magneWriter!=null){ magneWriter.close(); }
    	if(situWriter!=null){ situWriter.close(); }
        if(gyroWriter!=null){ gyroWriter.close();}
		if(preWriter!=null){ preWriter.close();}

    	//キャンセルボタンでor測定中にBACKボタンが押されてアプリが終了したなら記録用ファイルの破棄
    	if(logCancelled || logStarted){
	    	gpsFile.delete();
	    	accelFile.delete();
	    	magneFile.delete();
            gyroFile.delete();
	    	situFile.delete();
			audioFile.delete();
			preFile.delete();
    	}

    	//入力した名前の保存
		SharedPreferences.Editor editor = pref.edit();
		editor.putString("name", nameEdit.getText().toString() );
		editor.commit();

		super.onDestroy();
	}

	/* GPSが位置を取得した時の動作 */
	public void onLocationChanged(Location loc) {

		//条件入力時
		if(!logStarted){
			//一定の精度が得られている場合のみ測定開始可能になる
			if(loc.hasAccuracy() && loc.getAccuracy() < START_ACCURACY_THRESH){
				gpsStateText.setText(R.string.GPS_OK);
				if(isFixed == false){
					startstopButton.setVisibility(View.VISIBLE);
					isFixed = true;
				}
			}else{
				gpsStateText.setText(R.string.GPS_unfix);
				if(isFixed == true){
					startstopButton.setVisibility(View.INVISIBLE);
					isFixed = false;
				}
			}
			if(loc.hasAccuracy()){
				gpsStateText.append(Integer.toString((int)loc.getAccuracy()));
			}


		//記録時（uptime_nanoが0の時は無効）DOPも並べて記録する
		}else{
			if(uptime_nano!=0){
				gpsWriter.println(
				  uptime_nano + COMMA  //UPTIMENANO .. long[nsec]
				  + loc.getTime() + COMMA  //GPSTIME .. long[msec] ※UNIX時間
				  + (loc.hasSpeed() ? loc.getSpeed() : -1)+ COMMA //SPEED .. float[m/s] ※データ無しの場合は-1
				  + (float)loc.getLatitude() + COMMA //LAT .. double->float
				  + (float)loc.getLongitude() + COMMA //LON .. double->float
				  + (float)loc.getAltitude() + COMMA //ALT .. double->float
				  + loc.getBearing() + COMMA //BEARING .. float[deg]
				  + loc.getAccuracy() + COMMA //ACCURACY .. float[m]
				  + nmeaPdop + COMMA //PDOP[]
				  + nmeaHdop + COMMA //HDOP[]
				  + nmeaVdop + COMMA //VDOP[]
                  + this.nmeaUTC
				  );
			}
		}
	}

	/* GPSがオフになった時 */
	public void onProviderDisabled(String arg0) {
		gpsStateText.setText(R.string.GPS_unavailable);
		isFixed = false;
	}
	/* GPSがオンになった時 */
	public void onProviderEnabled(String provider) {
		gpsStateText.setText(R.string.GPS_unfix);
		isFixed = false;
		locManager.addNmeaListener(this);
		locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL , 0, this);
	}

	public void onStatusChanged(String provider, int status, Bundle extras) { }


	/* センサ類が値を取得した時の動作 */
	public void onAccuracyChanged(Sensor sensor, int accuracy) { }
	public void onSensorChanged(SensorEvent event) {


		//条件入力時
		if(!logStarted){
			//磁気（or磁気センサ）の異常を検出
			if(event.sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD){
				float norm = FloatMath.sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]);
				if(MAGNETIC_MIN < norm && norm < MAGNETIC_MAX){
					sensorStateText.setText(R.string.blank);
				}else{
					sensorStateText.setText(R.string.magne_abnormal);
				}

                if (magRefTime == 0){
                    magRefTime = event.timestamp;
                }else{
                    long elasp_e = event.timestamp - magRefTime;
                    magCount++;
                    if(elasp_e > 999999999) {
                        magRateText.setText(magCount + "HZ");
                        magCount = 0;
                        magRefTime = event.timestamp;
                    }
                }

			//加速度センサのサンプリングレートの異常を検出
			}else if(event.sensor.getType()==Sensor.TYPE_ACCELEROMETER ){
				if(accelRefTime == 0){
                        accelRefTime = event.timestamp;
                    }else{
                        long elapse = event.timestamp - accelRefTime;
                        accelCount++;
                        if(elapse > 999999999/*[nsec]*/){

                        accRateText.setText(accelCount + "HZ");
						if(accelCount < 23 || 41 < accelCount){ //22Hz以下もしくは42Hz以上の時
							sensorStateText.setText(R.string.accel_abnormal );
						}else{
							sensorStateText.setText(R.string.blank);
						}
						accelCount = 0;
						accelRefTime = event.timestamp;
					}
				}
			// gyro scope data
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED){
                if(gyroRefTime == 0){
                    gyroRefTime = event.timestamp;
                }else{
                    long elapse = event.timestamp - gyroRefTime;
                    gyroCount++;
                    if(elapse > 999999999/*[nsec]*/){

                        gyroRateText.setText(gyroCount + "HZ");
                        if(gyroCount < 23 || 41 < gyroCount){ //22Hz以下もしくは42Hz以上の時
                            sensorStateText.setText(R.string.gyro_abnormal );
                        }else{
                            sensorStateText.setText(R.string.blank);
                        }
                        gyroCount = 0;
                        gyroRefTime = event.timestamp;
                    }
                }

            } else if(event.sensor.getType()==Sensor.TYPE_PRESSURE ) {
				if (preRefTime == 0) {
					preRefTime = event.timestamp;
				} else {
					long elapse = event.timestamp - preRefTime;
					preCount++;
					if (elapse > 999999999/*[nsec]*/) {

						//preRateText.setText(preCount + "HZ");
						if (preCount < 23 || 41 < preCount) { //22Hz以下もしくは42Hz以上の時
							sensorStateText.setText(R.string.pre_abnormal);
						} else {
							sensorStateText.setText(R.string.blank);
						}
						preCount = 0;
						preRefTime = event.timestamp;
					}
				}
			}

		//記録時（uptime_nanoにもtimestampを格納）
		}else{
			//加速度
			if(event.sensor.getType()==Sensor.TYPE_ACCELEROMETER){
				uptime_nano = event.timestamp;
				accelWriter.println(
					event.timestamp + COMMA   //UPTIMENANO .. long[nsec]
					+ event.values[0] + COMMA //X軸加速度 .. float[m/s^2]
					+ event.values[1] + COMMA //Y軸加速度 .. float[m/s^2]
					+ event.values[2] );    //Z軸加速度 .. float[m/s^2]

				//磁気
			}else if(event.sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD){
				magneWriter.println(
					event.timestamp + COMMA   //UPTIMENANO .. long[nsec]
					+ event.values[0] + COMMA //X軸磁場 .. float[μT]
					+ event.values[1] + COMMA //Y軸磁場 .. float[μT]
					+ event.values[2] );    //Z軸磁場 .. float[μT]

			}else if(event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED){
                gyroWriter.println(
                    event.timestamp + COMMA
                        + event.values[0] + COMMA
                        + event.values[1] + COMMA
                        + event.values[2] + COMMA
                        + event.values[3] + COMMA
                        + event.values[4] + COMMA
                        + event.values[5]
                );
            }else if(event.sensor.getType()==Sensor.TYPE_PRESSURE){
				preWriter.println(
						event.timestamp + COMMA   //UPTIMENANO .. long[nsec]
								+ event.values[0] );

			}
		}
	}

	/* NMEA取得時の動作。書き込みではなくクラスのフィールドを更新するだけ */
	public void onNmeaReceived(long timestamp, String nmea){
		String[] data = nmea.split(COMMA , -1);
		if(data[0].equals(NMEA_GPGSA) ){
			nmeaPdop = data[GSA_PDOP_COLUMN ];
			nmeaHdop = data[GSA_HDOP_COLUMN ];
			nmeaVdop = data[GSA_VDOP_COLUMN ].split("\\*")[0];
		}else if(data[0].equals(this.NMEA_GPGGA)){
            this.nmeaUTC = data[this.GGA_UTC_COLUMN];
        }
	}

	/** 測定開始時の日付時刻をStringにして取得 **/
	public String getCurrentYYYYMMDDhhmm(){
		Calendar cal = Calendar.getInstance();
		int year = cal.get(Calendar.YEAR);
		int month = cal.get(Calendar.MONTH) + 1; //MONTHは0-11で返されるため
		int day = cal.get(Calendar.DAY_OF_MONTH);
		int hour = cal.get(Calendar.HOUR_OF_DAY);
		int minute = cal.get(Calendar.MINUTE);
		return String.format("%04d%02d%02d%02d%02d",year,month,day,hour,minute);
	}

}
