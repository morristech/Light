

package com.example.ledwisdom1.scene;


import android.arch.lifecycle.Observer;
import android.arch.lifecycle.ViewModelProviders;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.example.ledwisdom1.CallBack;
import com.example.ledwisdom1.CommonItemClickListener;
import com.example.ledwisdom1.Config;
import com.example.ledwisdom1.R;
import com.example.ledwisdom1.adapter.CommonItemAdapter;
import com.example.ledwisdom1.databinding.FragmentSceneBinding;
import com.example.ledwisdom1.device.entity.Lamp;
import com.example.ledwisdom1.fragment.ProduceAvatarFragment;
import com.example.ledwisdom1.model.CommonItem;
import com.google.gson.Gson;

import java.io.File;
import java.util.List;


/**
 * 同场景，不同之处在于设备选择，情景即可以选择设备也可以选择场景来控制
 * 添加或修改场景
 */


public class SceneFragment extends Fragment implements CallBack, ProduceAvatarFragment.Listener {

    public static final String TAG = SceneFragment.class.getSimpleName();
    private FragmentSceneBinding binding;
    private GroupSceneViewModel viewModel;
    private CommonItemAdapter itemAdapter;

    public static SceneFragment newInstance() {
        Bundle args = new Bundle();
        SceneFragment fragment = new SceneFragment();
        fragment.setArguments(args);
        return fragment;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_scene, container, false);
        binding.setHandler(this);
        itemAdapter = new CommonItemAdapter(itemClickListener);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        binding.recyclerView.setAdapter(itemAdapter);
        return binding.getRoot();
    }

    private CommonItemClickListener itemClickListener = commonItem -> {
        switch (commonItem.pos) {
            case 0:
                ProduceAvatarFragment.newInstance().show(getChildFragmentManager(), ProduceAvatarFragment.TAG);
                break;
            case 1:
                GroupSceneActivity.start(getActivity(), GroupSceneActivity.ACTION_EDIT_NAME);
                break;
            case 2:
                if (viewModel.MODE_ADD) {
                    GroupSceneActivity.start(getActivity(), GroupSceneActivity.ACTION_LAMP_LIST);
                }else {
                    GroupSceneActivity.start(getActivity(), GroupSceneActivity.ACTION_SELECTED_LAMP);
                }
                break;

        }
    };



    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        viewModel = ViewModelProviders.of(getActivity()).get(GroupSceneViewModel.class);
        boolean add = viewModel.MODE_ADD;
        binding.setAdd(add);
        binding.setTitle(add ? "新建情景" : "修改情景");
        itemAdapter.setItems(viewModel.generateItems());
        subscribeUI(viewModel);
    }


    private void subscribeUI(GroupSceneViewModel viewModel) {
        viewModel.groupDevicesObserver.observe(this, new Observer<List<Lamp>>() {
            @Override
            public void onChanged(@Nullable List<Lamp> lamps) {
                if (lamps != null) {
                    CommonItem device = itemAdapter.getItem(2);
                    device.observableValue.set(String.valueOf(lamps.size()));
                }
            }
        });

    }



    @Override
    public void handleClick(View view) {
        switch (view.getId()) {
            case R.id.iv_back:
                getActivity().onBackPressed();
                break;
            case R.id.confirm:
                if (viewModel.MODE_ADD) {
                    addScene();
                } else {
                    updateScene();
                }
                break;
            case R.id.delete:
                viewModel.deleteGroup(false);
                break;
        }
    }



    //    添加场景
    private void addScene() {
        if (TextUtils.isEmpty(viewModel.name)) {
            Toast.makeText(getContext(), "还没有设置名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(viewModel.imagePath)) {
            Toast.makeText(getContext(), "还没有设置图片", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> ids = viewModel.getSelectedLampIds();
        if (ids.isEmpty()) {
            Toast.makeText(getContext(), "还没有选择灯具", Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.isLoading.set(true);
        viewModel.groupSceneRequest.name = viewModel.name;
        viewModel.groupSceneRequest.pic = new File(viewModel.imagePath);
        viewModel.groupSceneRequest.deviceId = new Gson().toJson(ids);
        viewModel.addGroupRequest.setValue(viewModel.groupSceneRequest);

    }

    //    更新场景
    private void updateScene() {
        if (TextUtils.isEmpty(viewModel.name)) {
            Toast.makeText(getContext(), "还没有设置名称", Toast.LENGTH_SHORT).show();
            return;
        }
        viewModel.groupSceneRequest.name = viewModel.name;
        //如果是网络地址不处理，不是的话说明改动了图片
        if (!viewModel.imagePath.startsWith(Config.IMG_PREFIX)) {
            viewModel.groupSceneRequest.pic = new File(viewModel.imagePath);
        }
        viewModel.isLoading.set(true);
        viewModel.updateGroupRequest.setValue(viewModel.groupSceneRequest);
    }


    //    处理头像的回调
    @Override
    public void onItemClicked(File file) {
        CommonItem item = itemAdapter.getItem(0);
        item.observableValue.set(file.getAbsolutePath());
        //需要在viewModel中记录
        viewModel.imagePath = file.getAbsolutePath();
    }
}
