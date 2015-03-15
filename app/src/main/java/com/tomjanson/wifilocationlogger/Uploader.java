package com.tomjanson.wifilocationlogger;

import android.widget.Toast;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.apache.http.Header;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

public class Uploader {
    private static final String BASE_URL = MainActivity.UPLOAD_URL;

    private static AsyncHttpClient client = new AsyncHttpClient();

    private static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.get(getAbsoluteUrl(url), params, responseHandler);
    }

    private static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.post(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void upload(final MainActivity m, String pathToFile) {
        File file = new File(pathToFile);
        UUID uploadUuid = UUID.randomUUID();
        String targetFilename = m.UPLOAD_SECRET + "." + uploadUuid.toString();
        m.log.info("Upload UUID: {}", uploadUuid);

        RequestParams params = new RequestParams();
        try {
            params.put(targetFilename, file);
        } catch(FileNotFoundException e) {
            Toast.makeText(m, m.getString(R.string.upload_file_not_found), Toast.LENGTH_LONG).show();
            m.log.error("FileNotFoundException e = {}", e);
        }

        post("", params, new AsyncHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                Toast.makeText(m, m.getString(R.string.upload_successful), Toast.LENGTH_LONG).show();
                m.log.info("Upload success, statusCode: " + statusCode);
                //m.onUploadSuccess(statusCode, headers, responseBody);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
                Toast.makeText(m, m.getString(R.string.upload_failed), Toast.LENGTH_LONG).show();
                m.log.warn("Upload failure, statusCode: " + statusCode);
                if (error != null) {
                    m.log.error("Throwable error = {}", error);
                }
                //m.onUploadFailure(statusCode, headers, responseBody, error);
            }
        });
    }

    private static String getAbsoluteUrl(String relativeUrl) {
        return BASE_URL + relativeUrl;
    }
}