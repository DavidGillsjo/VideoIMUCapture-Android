package se.lth.math.videoimucapture;

import android.app.Dialog;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

public class InfoDialogFragment extends DialogFragment {
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        //Get result folder
        Bundle args = getArguments();


        // Use the Builder class for convenient dialog construction
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(args.getInt("title"))
                .setMessage(args.getString("message"))
                .setPositiveButton(R.string.info_dialog_button, null);
        // Create the AlertDialog object and return it
        return builder.create();
    }
}
