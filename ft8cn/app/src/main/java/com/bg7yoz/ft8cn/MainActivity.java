package com.bg7yoz.ft8cn;
/**
 * Main Activity for the FT8CN application. This app uses a Fragment framework, with each Fragment implementing different functionalities.
 * ----2022.5.6-----
 * Main functions include:
 * 1. Creating a MainViewModel instance. MainViewModel is used throughout the application lifecycle for features like recording and parsing.
 * 2. Requesting permissions for recording and storage.
 * 3. Managing Fragment navigation.
 * 4. Displaying notifications after USB serial port connection.
 * @author BG7YOZ
 * @date 2022.5.6
 */


import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.bg7yoz.ft8cn.bluetooth.BluetoothStateBroadcastReceive;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.connector.ConnectMode;

import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OnAfterQueryConfig;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.databinding.MainActivityBinding;
import com.bg7yoz.ft8cn.floatview.FloatView;
import com.bg7yoz.ft8cn.floatview.FloatViewButton;
import com.bg7yoz.ft8cn.grid_tracker.GridTrackerMainActivity;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.FreqDialog;
import com.bg7yoz.ft8cn.ui.SetVolumeDialog;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class MainActivity extends AppCompatActivity {
    private BluetoothStateBroadcastReceive mReceive;
    private static final String TAG = "MainActivity";
    private MainViewModel mainViewModel;
    private NavController navController;
    private static boolean animatorRunned = false;
    //private boolean animationEnd = false;

    private MainActivityBinding binding;
    private FloatView floatView;


    String[] permissions = new String[]{Manifest.permission.RECORD_AUDIO
            , Manifest.permission.ACCESS_COARSE_LOCATION
            , Manifest.permission.ACCESS_WIFI_STATE
            , Manifest.permission.BLUETOOTH
            , Manifest.permission.BLUETOOTH_ADMIN
            , Manifest.permission.MODIFY_AUDIO_SETTINGS
            , Manifest.permission.WAKE_LOCK
            , Manifest.permission.ACCESS_FINE_LOCATION};
    List<String> mPermissionList = new ArrayList<>();

    private static final int PERMISSION_REQUEST = 1;

    /**
     * Called when the activity is first created.
     * This method orchestrates the initialization of the main components of the activity,
     * including permissions, window settings, core application variables, ViewModel, ViewBinding,
     * UI observers, UI listeners, navigation, initial animations, and data loading.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down
     *                           then this Bundle contains the data it most recently supplied in onSaveInstanceState(Bundle).
     *                           Note: Otherwise it is null.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // Always call the superclass first

        // Step 1: Handle permissions based on Android version.
        updatePermissionsForAndroidS();
        // Step 2: Check and request necessary runtime permissions.
        checkPermission();
        // Step 3: Configure window appearance (fullscreen, keep screen on).
        setupWindowFlags();
        // Step 4: Initialize global variables and locale-specific settings.
        initializeCoreVariables();
        // Step 5: Set up the ViewModel and ViewBinding for the activity.
        setupViewModelAndBinding();
        // Step 6: Set the activity's content view using the binding's root.
        setContentView(binding.getRoot());

        // Step 7: Initialize the singleton ToastMessage utility.
        ToastMessage.getInstance();
        // Step 8: Register a broadcast receiver for Bluetooth state changes.
        registerBluetoothReceiver();
        // Note: Initial Bluetooth connection check is commented out to prevent issues during screen rotation.
        // if (mainViewModel.isBTConnected()) {
        //    //mainViewModel.setBlueToothOn(); // BV6LC This could cause errors on screen rotation.
        // }

        // Step 9: Set up LiveData observers to react to data changes from the ViewModel.
        setupObservers();
        // Step 10: Attach listeners to UI elements for user interaction.
        setupUIListeners();
        // Step 11: Configure the NavController and BottomNavigationView for app navigation.
        setupNavigation();

        // Step 12: Display version information in the welcome text view.
        binding.welcomTextView.setText(String.format(getString(R.string.version_info)
                , GeneralVariables.VERSION, GeneralVariables.BUILD_DATE));

        // Step 13: Manage the initial startup animation and the setup of the FloatView.
        handleInitialAnimationAndFloatView();
        // Step 14: Load essential application data.
        InitData();

        // Step 15: Populate the list of available USB devices.
        mainViewModel.getUsbDevice();

        // Step 16: Apply a blinking animation to the transmitting message text view.
        binding.transmittingMessageTextView.setAnimation(AnimationUtils.loadAnimation(this
                , R.anim.view_blink));
    }

    /**
     * Updates the permissions array for Android S (API level 31) and above
     * to include BLUETOOTH_CONNECT.
     */
    private void updatePermissionsForAndroidS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{Manifest.permission.RECORD_AUDIO
                    , Manifest.permission.ACCESS_COARSE_LOCATION
                    , Manifest.permission.ACCESS_WIFI_STATE
                    , Manifest.permission.BLUETOOTH
                    , Manifest.permission.BLUETOOTH_ADMIN
                    , Manifest.permission.BLUETOOTH_CONNECT // Added for Android S
                    , Manifest.permission.MODIFY_AUDIO_SETTINGS
                    , Manifest.permission.WAKE_LOCK
                    , Manifest.permission.ACCESS_FINE_LOCATION};
        }
    }

    /**
     * Sets window flags for full-screen mode and to keep the screen on.
     */
    private void setupWindowFlags() {
        // Set to full screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                , WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // Prevent screen sleep
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                , WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Initializes core application variables, including setting the main context
     * and determining language/locale settings.
     */
    private void initializeCoreVariables() {
        GeneralVariables.getInstance().setMainContext(getApplicationContext());
        // Check if the system language is Traditional Chinese
        GeneralVariables.isTraditionalChinese =
                getResources().getConfiguration().locale.getDisplayCountry().equals("中國");
        // Check if the locale is China, Hong Kong, Macau, or Taiwan
        GeneralVariables.isChina = (getResources().getConfiguration().locale
                .getLanguage().toUpperCase().startsWith("ZH"));
    }

    /**
     * Initializes the MainViewModel and MainActivityBinding.
     */
    private void setupViewModelAndBinding() {
        mainViewModel = MainViewModel.getInstance(this);
        binding = MainActivityBinding.inflate(getLayoutInflater());
        binding.initDataLayout.setVisibility(View.VISIBLE); // Show LOG page initially
    }

    /**
     * Sets up observers for LiveData instances from the MainViewModel.
     * These observers handle UI updates based on data changes.
     */
    private void setupObservers() {
        // Observe DEBUG messages
        GeneralVariables.mutableDebugMessage.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s.length() > 1) {
                    binding.debugLayout.setVisibility(View.VISIBLE);
                } else {
                    binding.debugLayout.setVisibility(View.GONE);
                }
                binding.debugMessageTextView.setText(s);
            }
        });

        // Observe recording state to show/hide UTC progress bar
        mainViewModel.mutableIsRecording.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.utcProgressBar.setVisibility(View.VISIBLE);
                } else {
                    binding.utcProgressBar.setVisibility(View.GONE);
                }
            }
        });

        // Observe timer changes to update the progress bar
        mainViewModel.timerSec.observe(this, new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                if (mainViewModel.ft8TransmitSignal.sequential == UtcTimer.getNowSequential()
                        && mainViewModel.ft8TransmitSignal.isActivated()) {
                    binding.utcProgressBar.setBackgroundColor(getColor(R.color.calling_list_isMyCall_color));
                } else {
                    binding.utcProgressBar.setBackgroundColor(getColor(R.color.progresss_bar_back_color));
                }
                binding.utcProgressBar.setProgress((int) ((aLong / 1000) % 15));
            }
        });

        // Observe whether it is a FlexRadio
        mainViewModel.mutableIsFlexRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    // Add FlexRadio configuration button
                    floatView.addButton(R.id.flex_radio, "flex_radio", R.drawable.flex_icon
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.flexRadioInfoFragment);
                                }
                            });
                } else {// Delete FlexRadio configuration button
                    floatView.deleteButtonByName("flex_radio");
                }
            }
        });

        // Observe whether it is a Xiegu radio
        mainViewModel.mutableIsXieguRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    // Add Xiegu configuration button
                    floatView.addButton(R.id.xiegu_radio, "xiegu_radio", R.drawable.xiegulogo32
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.xieguInfoFragment);
                                }
                            });
                } else {// Delete Xiegu configuration button
                    floatView.deleteButtonByName("xiegu_radio");
                }
            }
        });

        // Observe changes in the serial port device list
        mainViewModel.mutableSerialPorts.observe(this, new Observer<ArrayList<CableSerialPort.SerialPort>>() {
            @Override
            public void onChanged(ArrayList<CableSerialPort.SerialPort> serialPorts) {
                setSelectUsbDevice();
            }
        });

        // Observe the transmitting state
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(this,
                new Observer<Boolean>() {
                    @Override
                    public void onChanged(Boolean aBoolean) {
                        if (aBoolean) {
                            binding.transmittingLayout.setVisibility(View.VISIBLE);
                        } else {
                            binding.transmittingLayout.setVisibility(View.GONE);
                        }
                    }
                });

        // Observe changes in the transmitting content
        mainViewModel.ft8TransmitSignal.mutableTransmittingMessage.observe(this,
                new Observer<String>() {
                    @Override
                    public void onChanged(String s) {
                        binding.transmittingMessageTextView.setText(s);
                    }
                });
    }

    /**
     * Sets up UI listeners for various interactive elements.
     */
    private void setupUIListeners() {
        // Listener to hide the debug layout when clicked
        binding.debugLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.debugLayout.setVisibility(View.GONE);
            }
        });

        // Add click listener to close the transmitting message window
        binding.transmittingLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.transmittingLayout.setVisibility(View.GONE);
            }
        });

        // Close serial port device list button
        binding.closeSelectSerialPortImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.selectSerialPortLayout.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Sets up the navigation controller and the bottom navigation view.
     */
    private void setupNavigation() {
        // Used for Fragment navigation.
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null; // Assert not null
        navController = navHostFragment.getNavController();

        NavigationUI.setupWithNavController(binding.navView, navController);
        // This callback is added because after the APP actively navigates, it cannot return to the decoding interface
        binding.navView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                navController.navigate(item.getItemId());
                return true;
            }
        });
    }

    /**
     * Handles the initial animation display and FloatView initialization.
     * If the animation has not run before, it starts the animation.
     * Otherwise, it hides the initial data layout and initializes the FloatView directly.
     */
    private void handleInitialAnimationAndFloatView() {
        floatView = new FloatView(this, 32);
        if (!animatorRunned) {
            animationImage(); // Start animation if not run before
            animatorRunned = true;
        } else {
            binding.initDataLayout.setVisibility(View.GONE); // Hide init layout if animation already run
            InitFloatView(); // Initialize FloatView
        }
    }


    /**
     * Initializes and configures the FloatView, a floating action button menu.
     * This method adds the FloatView to the main container and populates it with several
     * action buttons:
     * - A button to toggle the visibility of the bottom navigation bar.
     * - A button to open the frequency selection dialog.
     * - A button to open the volume settings dialog.
     * - A button to launch the GridTrackerMainActivity.
     * The FloatView is positioned on the right side of the screen.
     * Dynamic buttons for FlexRadio and XieguRadio are added/removed via observers in `setupObservers`.
     */
    private void InitFloatView() {
        // Ensure floatView is initialized (typically done in handleInitialAnimationAndFloatView or onCreate)
        if (floatView == null) {
            floatView = new FloatView(this,32);
        }

        binding.container.addView(floatView); // Add FloatView to the main layout container
        floatView.setButtonMargin(0); // Set margin for individual buttons within FloatView
        floatView.setFloatBoard(FloatView.FLOAT_BOARD.RIGHT); // Anchor FloatView to the right edge

        floatView.setButtonBackgroundResourceId(R.drawable.float_button_style); // Apply custom background to buttons

        // Dynamically add buttons. Static IDs from R.id are recommended.
        // These IDs are defined in res/values/ids.xml (or similar).

        // 1. Navigation Toggle Button: Shows/hides the bottom navigation bar.
        floatView.addButton(R.id.float_nav, "float_nav", R.drawable.ic_baseline_fullscreen_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        FloatViewButton button = floatView.getButtonByName("float_nav");
                        if (binding.navView.getVisibility() == View.VISIBLE) {
                            binding.navView.setVisibility(View.GONE); // Hide navigation view
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_exit_24); // Change icon
                            }
                        } else {
                            binding.navView.setVisibility(View.VISIBLE); // Show navigation view
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_24); // Change icon
                            }
                        }
                    }
                });
        // Button to open frequency selection dialog
        floatView.addButton(R.id.float_freq, "float_freq", R.drawable.ic_baseline_freq_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new FreqDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });
        // Button to open volume setting dialog
        floatView.addButton(R.id.set_volume, "set_volume", R.drawable.ic_baseline_volume_up_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new SetVolumeDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });
        // Button to open Grid Tracker
        floatView.addButton(R.id.grid_tracker, "grid_tracker", R.drawable.ic_baseline_grid_tracker_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(getApplicationContext(), GridTrackerMainActivity.class);
                        startActivity(intent);
                    }
                });


//        floatView.addButton(R.id.flex_radio, "flex_radio", R.drawable.flex_icon
//                , new View.OnClickListener() {
//                    @Override
//                    public void onClick(View view) {
//                        navController.navigate(R.id.flexRadioInfoFragment);
//                    }
//                });

        floatView.initLocation(); // Initialize the location of the FloatView
    }

    /**
     * Orchestrates the initialization of various application data components.
     * It ensures that data is loaded only once. The process involves:
     * 1. Loading operation band data.
     * 2. Loading callsign, grid, and other configuration parameters.
     * 3. Loading followed callsigns and initializing the callsign database.
     * This method delegates to more specific loader methods.
     */
    private void InitData() {
        // Prevent re-initialization if data is already loaded
        if (mainViewModel.configIsLoaded) return;

        // Delegate to specific data loading methods
        loadOperationBandData();
        loadCallsignAndGridData();
        loadFollowCallsignsAndDatabase();
    }

    /**
     * Loads the operational band data required by the application.
     * It retrieves an instance of `OperationBand` using the base context
     * and assigns it to the `mainViewModel` if it's not already initialized.
     */
    private void loadOperationBandData() {
        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(getBaseContext());
        }
    }

    /**
     * Loads callsign and grid-related data.
     * This includes QSL DXCC information, general configuration parameters,
     * and Maidenhead grid information (prioritizing GPS data if available).
     * It also navigates to settings if callsign or grid is missing.
     */
    private void loadCallsignAndGridData() {
        mainViewModel.databaseOpr.getQslDxccToMap();

        // Get all configuration parameters
        mainViewModel.databaseOpr.getAllConfigParameter(new OnAfterQueryConfig() {
            @Override
            public void doOnBeforeQueryConfig(String KeyName) {
                // Actions before querying config (if any)
            }

            @Override
            public void doOnAfterQueryConfig(String KeyName, String Value) {
                mainViewModel.configIsLoaded = true; // Mark config as loaded
                // Here Maidenhead grid has been obtained from the database,
                // but if GPS can obtain it, use GPS data.
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getApplicationContext());
                // Adjust precision according to settings
                grid = grid.substring(0, Math.min((GeneralVariables.gpsPrecision * 2 + 4),
                        grid.length())
                );

                if (!grid.equals("")) { // Indicates that GPS data was obtained
                    GeneralVariables.setMyMaidenheadGrid(grid);
                    // Write to the database
                    mainViewModel.databaseOpr.writeConfig("grid", grid, null);
                }

                mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay);
                // If callsign or grid is empty, go to the settings interface
                if (GeneralVariables.getMyMaidenheadGrid().equals("")
                        || GeneralVariables.myCallsign.equals("")) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { // Navigate to the settings page
                            navController.navigate(R.id.menu_nav_config);
                        }
                    });
                }
            }
        });
    }

    /**
     * Loads followed callsigns from the database and initializes the callsign database.
     * It also retrieves the mapping of historical callsigns to grids.
     */
    private void loadFollowCallsignsAndDatabase() {
        // Map successfully communicated callsigns and grids from history
        new DatabaseOpr.GetCallsignMapGrid(mainViewModel.databaseOpr.getDb()).execute();

        mainViewModel.getFollowCallsignsFromDataBase();
        // Open the callsign location information database, currently as an in-memory database.
        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(getBaseContext(), null, 1);
        }
    }


    /**
     * Check for necessary permissions.
     * This method identifies which permissions are not yet granted and requests them.
     */
    private void checkPermission() {
        mPermissionList.clear();

        // Check which permissions have not been granted
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                mPermissionList.add(permission);
            }
        }

        // If the list is not empty, request permissions
        if (!mPermissionList.isEmpty()) {// Request permissions method
            String[] permissions = mPermissionList.toArray(new String[mPermissionList.size()]);// Convert List to array
            ActivityCompat.requestPermissions(MainActivity.this, permissions, PERMISSION_REQUEST);
        }
    }


    /**
     * Callback for the result from requesting permissions.
     * Regardless of whether the user denies permissions, proceed to the main page
     * and do not repeatedly request permissions.
     * @param requestCode The request code passed in requestPermissions(android.app.Activity, String[], int)
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions which is either PackageManager.PERMISSION_GRANTED or PackageManager.PERMISSION_DENIED. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            // If the request code does not match, delegate to the superclass.
            // This handles cases where other permission requests might be active.
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        // No specific action is taken here based on grantResults, as the app proceeds regardless.
        // The actual check for permissions and their impact is handled elsewhere,
        // or the app functions with degraded capabilities if permissions are denied.
    }


    /**
     * Dynamically populates and displays a list of available USB serial port devices.
     * This method is called when the `mutableSerialPorts` LiveData in `MainViewModel` changes.
     * It clears any existing views in `selectSerialPortLinearLayout` and then inflates
     * a new view (`select_serial_port_list_view_item`) for each detected serial port.
     * An OnClickListener is set for each item to connect to the selected rig via `mainViewModel.connectCableRig()`.
     * The visibility of the `selectSerialPortLayout` (which contains this list) is managed based on
     * whether any ports are available and if a rig is not already connected.
     */
    public void setSelectUsbDevice() {
        // Retrieve the current list of serial ports from the ViewModel.
        ArrayList<CableSerialPort.SerialPort> ports = mainViewModel.mutableSerialPorts.getValue();
        if (ports == null) return; // Exit if the list is null.

        // Clear any previously added views from the layout.
        binding.selectSerialPortLinearLayout.removeAllViews();

        // Iterate through the list of serial ports and create a view for each.
        for (int i = 0; i < ports.size(); i++) {
            final CableSerialPort.SerialPort currentPort = ports.get(i); // Get the current port.
            // Inflate the item layout for the serial port.
            View layout = LayoutInflater.from(getApplicationContext())
                    .inflate(R.layout.select_serial_port_list_view_item, binding.selectSerialPortLinearLayout, false);
            // It's good practice to set an ID that can be easily retrieved, though here 'i' is used directly.
            // layout.setId(View.generateViewId()); // Alternative for unique IDs.

            // Get the TextView from the inflated layout and set its text to the port's information.
            TextView textView = layout.findViewById(R.id.selectSerialPortListViewItemTextView);
            textView.setText(currentPort.information());

            // Add the newly created view to the LinearLayout.
            binding.selectSerialPortLinearLayout.addView(layout);

            // Set an OnClickListener for the item. When clicked, it attempts to connect to the rig.
            final int portIndex = i; // Use a final variable for use in lambda
            layout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // Instruct the ViewModel to connect to the selected serial port.
                    mainViewModel.connectCableRig(getApplicationContext(), ports.get(portIndex));
                    // Hide the serial port selection dialog after a selection is made.
                    binding.selectSerialPortLayout.setVisibility(View.GONE);
                }
            });
        }

        // Determine the visibility of the serial port selection dialog.
        // Show if there's at least one port and no rig is currently connected.
        if ((ports.size() >= 1) && (!mainViewModel.isRigConnected())) {
            binding.selectSerialPortLayout.setVisibility(View.VISIBLE);
        } else {
            // Hide if no ports are available or if a rig is already connected.
            binding.selectSerialPortLayout.setVisibility(View.GONE);
        }
    }

    /**
     * Delete all files in the specified folder.
     *
     * @param filePath The path of the specified folder.
     */
    public static void deleteFolderFile(String filePath) {
        try {
            File file = new File(filePath);// Get the specified path on the SD card
            File[] files = file.listFiles();// Get files or folders under the specified path on the SD card
            if (files == null) return; // No files or directory does not exist
            for (File value : files) {
                if (value.isFile()) {// If it is a file, delete it directly
                    File tempFile = new File(value.getPath());
                    tempFile.delete();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sets up and starts the initial animation for the logo and navigation view.
     * The animation includes fading out the logo and sliding in the navigation view.
     * After the animation ends, the FloatView is initialized and made visible.
     */
    private void animationImage() {
        // Animator for the bottom navigation view:
        // It translates the view vertically from 200 pixels below its final position to its final position.
        // The animation starts after a delay (implicit in the floatValues) and lasts for 3000ms.
        ObjectAnimator navigationAnimator = ObjectAnimator.ofFloat(binding.navView, "translationY", 200);
        navigationAnimator.setDuration(3000); // Total duration of this animator
        // The values 200, 200, 200, 0 mean:
        // Start at translationY = 200, stay there for a portion of the duration,
        // then animate to translationY = 0 (its original position).
        navigationAnimator.setFloatValues(200, 200, 200, 0);

        // Animator for the initial data layout (e.g., welcome screen or logo):
        // It fades out the layout from fully opaque (alpha=1f) to fully transparent (alpha=0f).
        // The animation also lasts for 3000ms.
        ObjectAnimator hideLogoAnimator = ObjectAnimator.ofFloat(binding.initDataLayout, "alpha", 1f, 1f, 1f, 0);
        hideLogoAnimator.setDuration(3000); // Total duration of this animator
        // The values 1f, 1f, 1f, 0f mean:
        // Start at alpha = 1f, stay there for a portion, then fade to alpha = 0f.

        // AnimatorSet to play both animations simultaneously.
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(navigationAnimator, hideLogoAnimator);

        // Add a listener to perform actions at the end of the animation set.
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
                // Code to run when the animation starts (optional).
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                // This block runs when both animations have completed.
                // animationEnd = true; // A flag that could be used elsewhere (currently commented out).

                // Hide the initial data layout as it has faded out.
                binding.initDataLayout.setVisibility(View.GONE);
                // Make the UTC progress bar visible.
                binding.utcProgressBar.setVisibility(View.VISIBLE);
                // Initialize and display the FloatView (floating action buttons).
                InitFloatView();
                // binding.floatView.setVisibility(View.VISIBLE); // Alternative if FloatView is a direct child (commented out).
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                // Code to run if the animation is cancelled (optional).
            }

            @Override
            public void onAnimationRepeat(Animator animator) {
                // Code to run if the animation repeats (optional, not typical for this setup).
            }
        });

        animatorSet.start(); // Begin the animations.
    }


    /**
     * This method is called when the activity is re-launched while at the top of the activity stack.
     * It checks if a USB device has been attached and triggers a refresh of the USB device list.
     * This is effective when android:launchMode="singleTask" is set for the activity.
     * @param intent The new intent that was started for the activity.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        // Check if the intent action indicates a USB device attachment
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            mainViewModel.getUsbDevice(); // Refresh the list of USB devices
        }
        super.onNewIntent(intent);
    }


    /**
     * Handles the back button press.
     * If the current destination is the start destination of the navigation graph,
     * it shows an exit confirmation dialog. Otherwise, it navigates up in the
     * navigation stack.
     */
    @Override
    public void onBackPressed() {
        // Check if the current destination is the start destination in the navigation graph
        if (navController.getGraph().getStartDestination() == navController.getCurrentDestination().getId()) {
            // If it's the last page, show an exit confirmation dialog
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.exit_confirmation))
                    .setPositiveButton(getString(R.string.exit)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    // Deactivate FT8 transmit signal if active
                                    if (mainViewModel.ft8TransmitSignal.isActivated()) {
                                        mainViewModel.ft8TransmitSignal.setActivated(false);
                                    }
                                    closeThisApp();// Exit the APP
                                }
                            }).setNegativeButton(getString(R.string.cancel)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    dialogInterface.dismiss(); // Dismiss the dialog
                                }
                            });
            builder.create().show(); // Create and show the dialog

        } else {// Exit the activity stack
            navController.navigateUp(); // Navigate up in the fragment stack
            //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR); // Example of changing screen orientation (commented out)
        }
    }

    /**
     * Closes the application.
     * This method deactivates the FT8 transmit signal, disconnects any connected rig,
     * stops the FT8 signal listener, nullifies the MainViewModel, and exits the application.
     */
    private void closeThisApp() {
        mainViewModel.ft8TransmitSignal.setActivated(false); // Deactivate transmission
        // Disconnect the rig if connected
        if (mainViewModel.baseRig != null) {
            if (mainViewModel.baseRig.getConnector() != null) {
                mainViewModel.baseRig.getConnector().disconnect();
            }
        }

        mainViewModel.ft8SignalListener.stopListen(); // Stop listening for FT8 signals
        mainViewModel = null; // Release MainViewModel instance
        System.exit(0); // Terminate the application
    }


    /**
     * Register Bluetooth action broadcast receiver.
     * This initializes and registers a BroadcastReceiver to listen for Bluetooth state changes,
     * device connections/disconnections, and SCO audio state updates.
     */
    private void registerBluetoothReceiver() {
        if (mReceive == null) {
            mReceive = new BluetoothStateBroadcastReceive(getApplicationContext(), mainViewModel);
        }
        IntentFilter intentFilter = new IntentFilter();
        // Actions for Bluetooth adapter state changes
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Actions for Bluetooth device ACL connection events
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        // Actions for SCO audio state updates
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE);
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY); // e.g., headphones unplugged
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_STATE);
        // Actions for Bluetooth connection state changes (generic)
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothAdapter.EXTRA_CONNECTION_STATE);
        intentFilter.addAction(BluetoothAdapter.EXTRA_STATE);
        // Specific states for adapter on/off (some devices might use these)
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_OFF");
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_ON");
        registerReceiver(mReceive, intentFilter); // Register the receiver
    }

    /**
     * Unregister Bluetooth action broadcast receiver.
     * This unregisters the previously registered BroadcastReceiver for Bluetooth events.
     */
    private void unregisterBluetoothReceiver() {
        if (mReceive != null) {
            unregisterReceiver(mReceive); // Unregister the receiver
            mReceive = null; // Release the receiver instance
        }
    }

    /**
     * Called when the activity is being destroyed.
     * This method ensures that the Bluetooth broadcast receiver is unregistered
     * to prevent memory leaks.
     */
    @Override
    protected void onDestroy() {
        unregisterBluetoothReceiver(); // Unregister Bluetooth receiver
        super.onDestroy();
    }


}