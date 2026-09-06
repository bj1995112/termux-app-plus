package com.termux.app.styling;

import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.termux.R;

import java.io.File;
import java.util.List;

public class TermuxStyleActivity extends AppCompatActivity {

    private TextView mCurrentColorText;
    private TextView mCurrentFontText;
    private TextView mPreviewTerminalText;

    private List<TermuxStyleManager.StyleItem> mColorSchemes;
    private List<TermuxStyleManager.StyleItem> mFonts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_style);

        MaterialToolbar toolbar = findViewById(R.id.style_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        mCurrentColorText = findViewById(R.id.current_color_scheme_text);
        mCurrentFontText = findViewById(R.id.current_font_text);
        mPreviewTerminalText = findViewById(R.id.preview_terminal_text);

        MaterialCardView cardColorScheme = findViewById(R.id.card_color_scheme);
        MaterialCardView cardFonts = findViewById(R.id.card_fonts);
        MaterialButton buttonReset = findViewById(R.id.button_reset_style);

        mColorSchemes = TermuxStyleManager.getColorSchemes(this);
        mFonts = TermuxStyleManager.getFonts(this);

        updateCurrentState();

        com.google.android.material.switchmaterial.SwitchMaterial switchDrawerAdapt = findViewById(R.id.switch_drawer_theme_adapt);
        if (switchDrawerAdapt != null) {
            switchDrawerAdapt.setChecked(TermuxStyleManager.isDrawerThemeAdaptEnabled(this));
            switchDrawerAdapt.setOnCheckedChangeListener((buttonView, isChecked) -> {
                TermuxStyleManager.setDrawerThemeAdaptEnabled(this, isChecked);
                TermuxStyleManager.sNeedReloadStyle = true;
                Toast.makeText(this, isChecked ? "已开启抽屉配色跟随终端" : "已恢复抽屉默认经典配色", Toast.LENGTH_SHORT).show();
            });
        }

        cardColorScheme.setOnClickListener(v -> showColorSchemeDialog());
        cardFonts.setOnClickListener(v -> showFontDialog());
        buttonReset.setOnClickListener(v -> showResetDialog());
    }

    private void updateCurrentState() {
        File homeDir = new File(getFilesDir(), "home");
        File termuxDir = new File(homeDir, ".termux");
        File fontFile = new File(termuxDir, "font.ttf");

        if (fontFile.exists() && fontFile.length() > 0) {
            try {
                Typeface tf = Typeface.createFromFile(fontFile);
                if (tf != null) {
                    mPreviewTerminalText.setTypeface(tf);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void showColorSchemeDialog() {
        String[] items = new String[mColorSchemes.size()];
        for (int i = 0; i < mColorSchemes.size(); i++) {
            items[i] = mColorSchemes.get(i).displayName;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.styling_color_scheme_title)
            .setItems(items, (dialog, which) -> {
                TermuxStyleManager.StyleItem selected = mColorSchemes.get(which);
                boolean success = TermuxStyleManager.applyColorScheme(this, selected.fileName);
                if (success) {
                    mCurrentColorText.setText(selected.displayName);
                    Toast.makeText(this, getString(R.string.styling_applied) + ": " + selected.displayName, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showFontDialog() {
        String[] items = new String[mFonts.size()];
        for (int i = 0; i < mFonts.size(); i++) {
            items[i] = mFonts.get(i).displayName;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.styling_font_title)
            .setItems(items, (dialog, which) -> {
                TermuxStyleManager.StyleItem selected = mFonts.get(which);
                boolean success = TermuxStyleManager.applyFont(this, selected.fileName);
                if (success) {
                    mCurrentFontText.setText(selected.displayName);
                    updateCurrentState();
                    Toast.makeText(this, getString(R.string.styling_applied) + ": " + selected.displayName, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showResetDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.styling_reset_title)
            .setMessage(R.string.styling_reset_confirm)
            .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                boolean success = TermuxStyleManager.resetToDefault(this);
                if (success) {
                    mCurrentColorText.setText(R.string.styling_color_scheme_summary);
                    mCurrentFontText.setText(R.string.styling_font_summary);
                    mPreviewTerminalText.setTypeface(Typeface.MONOSPACE);
                    Toast.makeText(this, R.string.styling_applied, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.action_no, null)
            .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
