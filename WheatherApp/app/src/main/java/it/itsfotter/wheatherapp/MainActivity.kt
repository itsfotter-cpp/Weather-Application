package it.itsfotter.wheatherapp

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import it.itsfotter.wheatherapp.databinding.ActivityMainBinding
import it.itsfotter.wheatherapp.models.WeatherResponse
import it.itsfotter.wheatherapp.network.WeatherService
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var mBinding: ActivityMainBinding
    private lateinit var mFusedLocationClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null
    private lateinit var mSharedPreferences: SharedPreferences

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)

        setupUI()

        if(!isLocationEnabled()) {
            Toast.makeText(
                this,
                "Your location provider is turned off. Please turn it on!",
                Toast.LENGTH_SHORT
            ).show()

            // This will redirect you to settings from where you need to turn on the location provider.
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }
        else {

            Dexter.withContext(this)
                .withPermissions(
                    ACCESS_FINE_LOCATION,
                    ACCESS_COARSE_LOCATION
                ).withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if(report!!.areAllPermissionsGranted()) {
                            requestLocationData()
                        }
                        if(report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "You have denied location permissions!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    /*
                    If the device has access to geolocation, but has been refused to the application
                     */
                    override fun onPermissionRationaleShouldBeShown(
                        p0: MutableList<PermissionRequest>?,
                        p1: PermissionToken?
                    ) {
                        showRationalDialogForPermissions()
                    }
                }).onSameThread()
                .check()
        }
    }

    /*
       We have to check if the location is enabled by the users.
     */
    private fun isLocationEnabled(): Boolean {

        /*
        I create the location service that is the 'locationManager'.
         */
        val locationManager: LocationManager =
                getSystemService(Context.LOCATION_SERVICE) as LocationManager

        /*
        We return if the GPS is enabled or the NETWORK PROVIDER.
        It is important because we don't have to know the exact position of the user, but he can be
        even at 50-100-1000 feets far away.
         */
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    }

    private fun showRationalDialogForPermissions() {
        AlertDialog.Builder(this@MainActivity)
            .setMessage("It looks like you have turned off permissions required for this feature.")
            .setPositiveButton("GO TO SETTINGS") {
                _,_ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancel") {
                dialog,_ ->
                dialog.dismiss()
            }.show()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationData() {

        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        mFusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            val mLastLocation: Location? = locationResult.lastLocation
            val latitude = mLastLocation!!.latitude
            Log.i("Current Latitude", "$latitude")

            val longitude = mLastLocation.longitude
            Log.i("Current longitude", "$longitude")
            getLocationWeatherDetails(latitude, longitude)
        }
    }

    private fun getLocationWeatherDetails(latitude: Double, longitude: Double) {
        if(Constants.isNetworkAvailable(this)) {

            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service: WeatherService = retrofit.create(WeatherService::class.java)

            val listCall: Call<WeatherResponse> = service.getWeather(
                latitude,
                longitude,
                Constants.METRIC_UNIT,
                Constants.APP_ID
            )

            showCustomProgressDialog()

            /*
            enqueue() is a part of Call object.
             */
            listCall.enqueue(object : Callback<WeatherResponse>{
                @SuppressLint("CommitPrefEdits")
                @RequiresApi(Build.VERSION_CODES.N)
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if(response.isSuccessful) {
                        hideProgressDialog()
                        val weatherList: WeatherResponse? = response.body()
                        Log.i("Response Result", "$weatherList")

                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        editor.apply()

                        setupUI()
                    }
                    else {
                        val rc = response.code()
                        when(rc) {
                            400 -> {
                                Log.e("Error 400", "Bad Connection")
                            }
                            404 -> {
                                Log.e("Error 401", "Not Found")
                            }
                            else -> {
                                Log.e("Error", "Generic Error")
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("Error onFailure", t.message.toString())
                    hideProgressDialog()
                }

            })

            /*
            Toast.makeText(
                this@MainActivity,
                "You have connected to the Internet. Now you can make your requests!",
                Toast.LENGTH_SHORT).show()
             */
        }
    }

    private fun showCustomProgressDialog() {
        mProgressDialog = Dialog(this)

        mProgressDialog!!.setContentView(R.layout.dialog_custom_progress)

        mProgressDialog!!.show()
    }

    private fun hideProgressDialog() {
        if(mProgressDialog != null) {
            mProgressDialog!!.dismiss()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.action_refresh -> {
                requestLocationData()
                true
            }
            else -> { super.onOptionsItemSelected(item) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("SetTextI18n")
    fun setupUI() {

        val weatherResponseJsonString = mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA, "")

        if(!weatherResponseJsonString.isNullOrEmpty()) {

            /*
            We pass to the Gson().fromJson the name of the string stored in the sharedPreferences
            and also the name of the class to be interpreted
             */
            val weatherList = Gson().fromJson(weatherResponseJsonString, WeatherResponse::class.java)

            val df = DecimalFormat("#.##")

            for (i in weatherList.weather.indices) {
                Log.i("WEATHER_NAME", weatherList.weather.toString())

                mBinding.tvMain.text = weatherList.weather[i].main
                mBinding.tvMainDescription.text = weatherList.weather[i].description
                mBinding.tvTemp.text = weatherList.main.temp.roundToInt().toString() + getUnit(
                    application.resources.configuration.locales.toString()
                )

                mBinding.tvSunriseTime.text = unixTime(weatherList.sys.sunrise)
                mBinding.tvSunsetTime.text = unixTime(weatherList.sys.sunset)

                mBinding.tvHumidity.text = weatherList.main.humidity.toString() + " %"
                mBinding.tvMin.text = weatherList.main.temp_min.roundToInt().toString() + getUnit(
                    application.resources.configuration.locales.toString()
                ) + " min"
                mBinding.tvMax.text = weatherList.main.temp_max.roundToInt().toString() + getUnit(
                    application.resources.configuration.locales.toString()
                ) + " max"
                mBinding.tvSpeed.text = df.format((weatherList.wind.speed * (1.60934))).toString()
                mBinding.tvName.text = weatherList.name
                mBinding.tvCountry.text = weatherList.sys.country

                when (weatherList.weather[i].icon) {
                    "01d" -> mBinding.ivMain.setImageResource(R.drawable.sunny)
                    "02d" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "03d" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "04d" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "04n" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "10d" -> mBinding.ivMain.setImageResource(R.drawable.rain)
                    "11d" -> mBinding.ivMain.setImageResource(R.drawable.storm)
                    "13d" -> mBinding.ivMain.setImageResource(R.drawable.snowflake)
                    "01n" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "02n" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "03n" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "10n" -> mBinding.ivMain.setImageResource(R.drawable.cloud)
                    "11n" -> mBinding.ivMain.setImageResource(R.drawable.rain)
                    "13n" -> mBinding.ivMain.setImageResource(R.drawable.snowflake)
                }
            }
        }
    }

    private fun getUnit(position: String): String {
        var value = "°C"
        if("US" == position || "LR" == position || "MM" == position) {
            value = "°F"
        }
        return value
    }

    @SuppressLint("SimpleDateFormat")
    private fun unixTime(timex: Long): String {
        /*
        We have to convert the milliseconds timex in something readable
        from the Date function.
         */
        val date = Date(timex * 1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.ITALY)
        sdf.timeZone = TimeZone.getDefault()

        return sdf.format(date)
    }

}