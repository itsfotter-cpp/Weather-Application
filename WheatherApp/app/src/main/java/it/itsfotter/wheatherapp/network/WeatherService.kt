package it.itsfotter.wheatherapp.network

import it.itsfotter.wheatherapp.models.WeatherResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface WeatherService {

    @GET("2.5/weather")
    fun getWeather(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("units") units: String?,
        @Query("appid") appid: String?
    ): Call<WeatherResponse>

    /*
    Notice that the response is "WeatherResponse", i.e. from the call we want all the information
    that we have defined in that model "WeatherResponse".
     */

}