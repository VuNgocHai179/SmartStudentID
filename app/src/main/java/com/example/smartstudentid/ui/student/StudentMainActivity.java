package com.example.smartstudentid.ui.student;

import android.os.Bundle;
import android.widget.TextView;

import com.example.smartstudentid.security.BaseSecureActivity;

/**
 * Student Main Activity container for the 4 functional tabs (to be fully populated in Step 12).
 */
public class StudentMainActivity extends BaseSecureActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("StudentMainActivity Ready (4 Tabs will be populated in Step 12)");
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}
