package com.xmission.trevin.android.notes.ui;

import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;

import android.view.View;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;

import org.hamcrest.Matcher;

/**
 * A custom view action that calls {@link View#requestFocus()}.
 */
public class FocusAction implements ViewAction {

    private static final FocusAction INSTANCE = new FocusAction();

    public static FocusAction requestFocus() {
        return INSTANCE;
    }

    @Override
    public Matcher<View> getConstraints() {
        return isDisplayed();
    }

    @Override
    public String getDescription() {
        return "request focus";
    }

    @Override
    public void perform(UiController uiController, View view) {
        view.requestFocus();
    }

}
