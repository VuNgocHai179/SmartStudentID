package com.example.smartstudentid;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.smartstudentid.ui.auth.LoginPinActivity;

/**
 * Entry launcher activity that immediately routes the user to LoginPinActivity.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, LoginPinActivity.class));
        finish();
    }
}
