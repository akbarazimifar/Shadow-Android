package su.sres.securesms.mediasend;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.RecyclerView;

import com.annimon.stream.Stream;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import su.sres.securesms.ClearAvatarPromptActivity;
import su.sres.securesms.R;
import su.sres.securesms.permissions.Permissions;
import su.sres.securesms.util.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

public class AvatarSelectionBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private static final String ARG_OPTIONS      = "options";
    private static final String ARG_REQUEST_CODE = "request_code";
    private static final String ARG_IS_GROUP     = "is_group";

    public static DialogFragment create(boolean includeClear, boolean includeCamera, short requestCode, boolean isGroup) {
        DialogFragment        fragment         = new AvatarSelectionBottomSheetDialogFragment();
        List<SelectionOption> selectionOptions = new ArrayList<>(3);
        Bundle                args             = new Bundle();

        if (includeCamera) {
            selectionOptions.add(SelectionOption.CAPTURE);
        }

        selectionOptions.add(SelectionOption.GALLERY);

        if (includeClear) {
            selectionOptions.add(SelectionOption.DELETE);
        }

        String[] options = Stream.of(selectionOptions)
                .map(SelectionOption::getCode)
                .toArray(String[]::new);

        args.putStringArray(ARG_OPTIONS, options);
        args.putShort(ARG_REQUEST_CODE, requestCode);
        args.putBoolean(ARG_IS_GROUP, isGroup);
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(DialogFragment.STYLE_NORMAL,
                ThemeUtil.isDarkTheme(requireContext()) ? R.style.Theme_Signal_BottomSheetDialog_Fixed
                        : R.style.Theme_Signal_Light_BottomSheetDialog_Fixed);

        super.onCreate(savedInstanceState);

        if (getOptionsCount() == 1) {
            askForPermissionIfNeededAndLaunch(getOptionsFromArguments().get(0));
        }
    }

    @Override
    public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.avatar_selection_bottom_sheet_dialog_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView recyclerView = view.findViewById(R.id.avatar_selection_bottom_sheet_dialog_fragment_recycler);
        recyclerView.setAdapter(new SelectionOptionAdapter(getOptionsFromArguments(), this::askForPermissionIfNeededAndLaunch));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    @SuppressWarnings("ConstantConditions")
    private int getOptionsCount() {
        return requireArguments().getStringArray(ARG_OPTIONS).length;
    }

    @SuppressWarnings("ConstantConditions")
    private List<SelectionOption> getOptionsFromArguments() {
        String[] optionCodes = requireArguments().getStringArray(ARG_OPTIONS);

        return Stream.of(optionCodes).map(SelectionOption::fromCode).toList();
    }

    private void askForPermissionIfNeededAndLaunch(@NonNull SelectionOption option) {
        if (option == SelectionOption.CAPTURE) {
            Permissions.with(this)
                    .request(Manifest.permission.CAMERA)
                    .ifNecessary()
                    .onAllGranted(() -> launchOptionAndDismiss(option))
                    .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.AvatarSelectionBottomSheetDialogFragment__taking_a_photo_requires_the_camera_permission, Toast.LENGTH_SHORT)
                            .show())
                    .execute();
        } else if (option == SelectionOption.GALLERY) {
            Permissions.with(this)
                    .request(Manifest.permission.READ_EXTERNAL_STORAGE)
                    .ifNecessary()
                    .onAllGranted(() -> launchOptionAndDismiss(option))
                    .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.AvatarSelectionBottomSheetDialogFragment__viewing_your_gallery_requires_the_storage_permission, Toast.LENGTH_SHORT)
                            .show())
                    .execute();
        } else {
            launchOptionAndDismiss(option);
        }
    }

    private void launchOptionAndDismiss(@NonNull SelectionOption option) {
        Intent intent = createIntent(requireContext(), option, requireArguments().getBoolean(ARG_IS_GROUP));

        int requestCode = requireArguments().getShort(ARG_REQUEST_CODE);
        if (getParentFragment() != null) {
            requireParentFragment().startActivityForResult(intent, requestCode);
        } else {
            requireActivity().startActivityForResult(intent, requestCode);
        }

        dismiss();
    }

    private static Intent createIntent(@NonNull Context context, @NonNull SelectionOption selectionOption, boolean isGroup) {
        switch (selectionOption) {
            case CAPTURE:
                return AvatarSelectionActivity.getIntentForCameraCapture(context);
            case GALLERY:
                return AvatarSelectionActivity.getIntentForGallery(context);
            case DELETE:
                return isGroup ? ClearAvatarPromptActivity.createForGroupProfilePhoto()
                        : ClearAvatarPromptActivity.createForUserProfilePhoto();
            default:
                throw new IllegalStateException("Unknown option: " + selectionOption);
        }
    }

    private enum SelectionOption {
        CAPTURE("capture", R.string.AvatarSelectionBottomSheetDialogFragment__take_photo, R.drawable.ic_camera_24),
        GALLERY("gallery", R.string.AvatarSelectionBottomSheetDialogFragment__choose_from_gallery, R.drawable.ic_photo_24),
        DELETE("delete", R.string.AvatarSelectionBottomSheetDialogFragment__remove_photo, R.drawable.ic_trash_24);

        private final              String code;
        private final @StringRes   int    label;
        private final @DrawableRes int    icon;

        SelectionOption(@NonNull String code, @StringRes int label, @DrawableRes int icon) {
            this.code  = code;
            this.label = label;
            this.icon  = icon;
        }

        public @NonNull String getCode() {
            return code;
        }

        static SelectionOption fromCode(@NonNull String code) {
            for (SelectionOption option : values()) {
                if (option.code.equals(code)) {
                    return option;
                }
            }

            throw new IllegalStateException("Unknown option: " + code);
        }
    }

    private static class SelectionOptionViewHolder extends RecyclerView.ViewHolder {

        private final AppCompatTextView optionView;

        SelectionOptionViewHolder(@NonNull View itemView, @NonNull Consumer<Integer> onClick) {
            super(itemView);
            itemView.setOnClickListener(v -> {
                if (getAdapterPosition() != RecyclerView.NO_POSITION) {
                    onClick.accept(getAdapterPosition());
                }
            });

            optionView = (AppCompatTextView) itemView;
        }

        void bind(@NonNull SelectionOption selectionOption) {
            optionView.setCompoundDrawablesWithIntrinsicBounds(ContextCompat.getDrawable(optionView.getContext(), selectionOption.icon), null, null, null);
            optionView.setText(selectionOption.label);
        }
    }

    private static class SelectionOptionAdapter extends RecyclerView.Adapter<SelectionOptionViewHolder> {

        private final List<SelectionOption>     options;
        private final Consumer<SelectionOption> onOptionClicked;

        private SelectionOptionAdapter(@NonNull List<SelectionOption> options, @NonNull Consumer<SelectionOption> onOptionClicked) {
            this.options         = options;
            this.onOptionClicked = onOptionClicked;
        }

        @NonNull
        @Override
        public SelectionOptionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.avatar_selection_bottom_sheet_dialog_fragment_option, parent, false);
            return new SelectionOptionViewHolder(view, (position) -> onOptionClicked.accept(options.get(position)));
        }

        @Override
        public void onBindViewHolder(@NonNull SelectionOptionViewHolder holder, int position) {
            holder.bind(options.get(position));
        }

        @Override
        public int getItemCount() {
            return options.size();
        }
    }
}