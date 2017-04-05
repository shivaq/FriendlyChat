/**
 * Copyright Google Inc. All Rights Reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.firebase.udacity.friendlychat;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.firebase.ui.auth.AuthUI;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.udacity.friendlychat.model.FriendlyMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    public static final String ANONYMOUS = "anonymous";
    public static final int DEFAULT_MSG_LENGTH_LIMIT = 1000;
    public static final int RC_SIGN_IN = 1;
    private static final int RC_PHOTO_PICKER = 2;

    private ListView mMessageListView;
    private MessageAdapter mMessageAdapter;
    private ProgressBar mProgressBar;
    private EditText mMessageEditText;
    private Button mSendButton;
    private String mUsername;

    private FirebaseDatabase mFirebaseDatabase;
    private DatabaseReference mMessagesDatabaseReference;
    private ChildEventListener mChildEventListener;
    private FirebaseAuth mFirebaseAuth;
    private FirebaseAuth.AuthStateListener mAuthStateListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        mUsername = ANONYMOUS;

        // DB アクセスのためのエントリーポイント
        mFirebaseDatabase = FirebaseDatabase.getInstance();
        // 認証Service用インスタンス取得
        mFirebaseAuth = FirebaseAuth.getInstance();
        // DB の特定のパートにアクセスするためのもの
        mMessagesDatabaseReference = mFirebaseDatabase.getReference().child("messages");

        // Initialize references to views
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        mMessageListView = (ListView) findViewById(R.id.messageListView);
        mMessageEditText = (EditText) findViewById(R.id.messageEditText);
        mSendButton = (Button) findViewById(R.id.sendButton);

        // Initialize message ListView and its adapter
        List<FriendlyMessage> friendlyMessages = new ArrayList<>();
        mMessageAdapter = new MessageAdapter(this, R.layout.item_message, friendlyMessages);
        mMessageListView.setAdapter(mMessageAdapter);

        // Initialize progress bar
        mProgressBar.setVisibility(ProgressBar.INVISIBLE);

        // Enable Send button when there's text to send
        // EditTextにListener。入力されると Send ボタンの有効化条件分岐
        mMessageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().trim().length() > 0) {
                    mSendButton.setEnabled(true);
                } else {
                    mSendButton.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        mMessageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT)});

        // AuthStateListener を定義// 二つの状態 サインイン/サインアウト をリッスン
        mAuthStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    // User is singed in
                    onSignedInInitialize(user.getDisplayName());
                } else {
                    //Current user がいない → User is signed out
                    onSignedOutCleanup();
                    // ログイン Activity は AuthUI が生成する。
                    // https://github.com/firebase/FirebaseUI-Android/blob/master/auth/README.md#sign-in-examples
                    // ログイン Activity に startActivityForResult を使って遷移
                    //  →onActivityResult() がコールされる
                    startActivityForResult(
                            AuthUI.getInstance()
                                    .createSignInIntentBuilder()
                                    // ユーザの信頼性を自動保存するような感じの機能。
                                    // 通常はデフォルトの true でよいので、あえて定義しない。
                                    // ここでは、勉強のためfalse で定義している。
                                    .setIsSmartLockEnabled(false)
                                    .setProviders(Arrays.asList(
                                            new AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER).build(),
                                            new AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER).build()))
                                    .build(),
                            // Request Code
                            RC_SIGN_IN);
                }
            }
        };
    }

    @Override
    // onResume 前に呼ばれる つまりmAuthStateListener がコールされる前
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // startActivityForResult のリクエストコードをチェック
        if (requestCode == RC_SIGN_IN) {
            if (resultCode == RESULT_OK) {
                // Sign-in succeeded, set up the UI
                Toast.makeText(this, "Signed in!", Toast.LENGTH_SHORT).show();
            } else if(resultCode == RESULT_CANCELED){
                // Sign-in was canceled by the user, finish the activity
                Toast.makeText(this, "Sign in canceled", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFirebaseAuth.addAuthStateListener(mAuthStateListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 特に複数の Activity を扱う場合は、Listener のアタッチデタッチは重要
        if (mAuthStateListener != null) {
            mFirebaseAuth.removeAuthStateListener(mAuthStateListener);
            mMessageAdapter.clear();
            detachDatabaseReadListener();
        }
    }

    // サインインが完了したら
    private void onSignedInInitialize(String userName) {
        mUsername = userName;
        attatchDatabaseReadListener();
    }

    private void onSignedOutCleanup() {
        mUsername = ANONYMOUS;
        mMessageAdapter.clear();
        detachDatabaseReadListener();
    }

    private void attatchDatabaseReadListener() {
        // Listener のアタッチデタッチが重複しないよう確認
        if(mChildEventListener == null){
            // RealtimeDB の変更に対するイベントリスナをセット
            mChildEventListener = new ChildEventListener() {

                @Override
                // コンテンツが新規追加された時
                public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                    // DataSnapshot から値を取得// deserialize される
                    FriendlyMessage friendlyMessage = dataSnapshot.getValue(FriendlyMessage.class);
                    mMessageAdapter.add(friendlyMessage);
                }
                // DataSnapshot →Firebase のデータ及び、いつどの位置に

                @Override
                // 既存のコンテンツが変更された時
                public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                }

                @Override
                public void onChildRemoved(DataSnapshot dataSnapshot) {
                }

                @Override
                // リスト上のポジションが変更された時
                public void onChildMoved(DataSnapshot dataSnapshot, String s) {
                }

                @Override
                // 変更を試みてエラーが返された時など// read権限なしで読み込み試行時など
                public void onCancelled(DatabaseError databaseError) {
                }
            };
            // ChildListenerを追加
            mMessagesDatabaseReference.addChildEventListener(mChildEventListener);
        }
    }


    private void detachDatabaseReadListener() {
        if(mChildEventListener != null){
            mMessagesDatabaseReference.removeEventListener(mChildEventListener);
            mChildEventListener = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()){
            case R.id.sign_out_menu:
                // サインアウト用メソッド
                AuthUI.getInstance().signOut(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // Send button sends a message and clears the EditText
    @OnClick(R.id.sendButton)
    void onSendClicked() {

        // Model のデータをEditText から取得
        FriendlyMessage friendlyMessage =
                new FriendlyMessage(mMessageEditText.getText().toString(), mUsername, null);

        Timber.d("MainActivity:onSendClicked: user %s'sfriendlyMessage is %s", mUsername, friendlyMessage.toString());
        // Clear input box
        mMessageEditText.setText("");
        // RemoteDB にデータをプッシュ
        mMessagesDatabaseReference.push().setValue(friendlyMessage);
    }

    // shows an image picker to upload a image for a message
    @OnClick(R.id.photoPickerButton)
    void onPickPhotoClicked(){
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        startActivityForResult(Intent.createChooser(intent, "Complete action using"),
                RC_PHOTO_PICKER);
    }

}
