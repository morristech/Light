package com.kimascend.light.repository;


import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.arch.lifecycle.Observer;
import android.arch.persistence.room.Dao;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.ArrayMap;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import com.kimascend.light.api.ApiResponse;
import com.kimascend.light.api.KimAscendService;
import com.kimascend.light.api.NetWork;
import com.kimascend.light.api.Resource;
import com.kimascend.light.app.AppExecutors;
import com.kimascend.light.app.SmartLightApp;
import com.kimascend.light.clock.Clock;
import com.kimascend.light.clock.ClockList;
import com.kimascend.light.clock.ClockRequest;
import com.kimascend.light.clock.ClockResult;
import com.kimascend.light.common.BindingAdapters;
import com.kimascend.light.common.NetWorkBoundResource;
import com.kimascend.light.common.Predicate;
import com.kimascend.light.common.RequestCreator;
import com.kimascend.light.database.ClockDao;
import com.kimascend.light.database.GroupDao;
import com.kimascend.light.database.LampDao;
import com.kimascend.light.database.SceneDao;
import com.kimascend.light.database.SmartLightDataBase;
import com.kimascend.light.database.UserDao;
import com.kimascend.light.device.entity.AddHubRequest;
import com.kimascend.light.device.entity.Lamp;
import com.kimascend.light.device.entity.LampList;
import com.kimascend.light.home.entity.Group;
import com.kimascend.light.home.entity.GroupList;
import com.kimascend.light.home.entity.Hub;
import com.kimascend.light.home.entity.HubList;
import com.kimascend.light.mesh.AddMeshResult;
import com.kimascend.light.mesh.DefaultMesh;
import com.kimascend.light.mesh.Mesh;
import com.kimascend.light.mesh.MeshList;
import com.kimascend.light.mesh.ReportMesh;
import com.kimascend.light.model.RequestResult;
import com.kimascend.light.model.User;
import com.kimascend.light.scene.AddGroupSceneResult;
import com.kimascend.light.scene.DeviceSetting;
import com.kimascend.light.scene.GroupDevice;
import com.kimascend.light.scene.GroupRequest;
import com.kimascend.light.scene.Scene;
import com.kimascend.light.scene.SceneList;
import com.kimascend.light.scene.SceneRequest;
import com.kimascend.light.user.Profile;
import com.telink.bluetooth.light.OnlineStatusNotificationParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/**
 * 负责网络请求 查询数据库数据 为ViewModel提供数据
 * <p>
 * 数据中心 单列模式
 * 一旦实例化，首先从数据库表profile加载个人数据，没有使用SharePreference防止缓存被清空
 * 如果profile为空 1.进入登录页面
 * 2.不为空说明已经登录进入首页
 * 2.1 meshId为空 新建空mesh
 * 2.3 meshId不为空 从后台加载最新的mesh信息
 * <p>
 * Profile采用LiveData封装，当其中meshId被修改就会根据meshId重新加载最新的Mesh，头像其他个人资料暂未实行
 */
public class HomeRepository {
    private static final String TAG = HomeRepository.class.getSimpleName();
    private static HomeRepository sDataRepository;
    private final SmartLightDataBase mDataBase;
    private final KimAscendService kimService;
    private final UserDao userDao;
    private final LampDao lampDao;
    private final GroupDao groupDao;
    private final SceneDao sceneDao;
    private final ClockDao clockDao;
    private final AppExecutors executors;


    private HomeRepository(Context context) {
        mDataBase = SmartLightDataBase.INSTANCE(context);
        userDao = mDataBase.user();
        lampDao = mDataBase.lamp();
        groupDao = mDataBase.group();
        sceneDao = mDataBase.scene();
        clockDao = mDataBase.clock();
        kimService = NetWork.kimService();
        executors = SmartLightApp.INSTANCE().appExecutors();
    }

    public static HomeRepository INSTANCE(Context context) {
        if (sDataRepository == null) {
            synchronized (HomeRepository.class) {
                if (sDataRepository == null) {
                    sDataRepository = new HomeRepository(context);
                }
            }
        }
        return sDataRepository;
    }


    public int getLampsNum() {
        String meshId = getMeshId();
        return lampDao.getLampNum(meshId);
    }

    private String getMeshId() {
//        Profile profile = SmartLightApp.INSTANCE().getProfile();
//        return profile != null ? profile.meshId : "";
        DefaultMesh defaultMesh = SmartLightApp.INSTANCE().getDefaultMesh();
        return defaultMesh.id;
    }

    private String getUserId() {
        Profile profile = SmartLightApp.INSTANCE().getProfile();
        return profile != null ? profile.userId : "";
    }

    public LiveData<User> getUserInfo() {
        MediatorLiveData<User> result = new MediatorLiveData<>();
        LiveData<ApiResponse<User>> userInfo = kimService.getUserInfo();
        result.addSource(userInfo, new Observer<ApiResponse<User>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<User> apiResponse) {
                if (apiResponse.isSuccessful()) {
                    result.setValue(apiResponse.body);
                } else {
                    result.setValue(null);
                }
            }
        });
        return result;

    }


    /**
     * 分两步
     * 1.创建场景
     * 2.添加设备到场景
     *
     * @param groupRequest 添加场景的参数 含上传的device ids，上传之前就要判断是否存在
     * @return
     */
    public LiveData<AddGroupSceneResult> addGroup(GroupRequest groupRequest) {
        MediatorLiveData<AddGroupSceneResult> addGroupResult = new MediatorLiveData<>();
        ArrayMap<String, String> map = new ArrayMap<>();
        map.put("name", groupRequest.name);
        map.put("meshId", getMeshId());
        RequestBody requestFile = RequestBody.create(RequestCreator.MEDIATYPE, groupRequest.pic);
        // MultipartBody.Part用来发送真实的文件名
        MultipartBody.Part icon = MultipartBody.Part.createFormData("pic", groupRequest.pic.getName(), requestFile);
        //先创建场景
        LiveData<ApiResponse<AddGroupSceneResult>> createGroupResponse = kimService.createGroup(icon, map);
        addGroupResult.addSource(createGroupResponse, new Observer<ApiResponse<AddGroupSceneResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<AddGroupSceneResult> apiResponse) {
                addGroupResult.removeSource(createGroupResponse);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    AddGroupSceneResult addDeviceResult = apiResponse.body;
                    //这个是后台的数据库的标识
                    groupRequest.groupId = addDeviceResult.id;
//                    仅需要groupid和deviceids
                    RequestBody requestBody = RequestCreator.requestAddLampToGroup(groupRequest);
                    //开始添加设备到场景
                    LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addDeviceToGroup(requestBody);
                    addGroupResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
                        @Override
                        public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                            addGroupResult.removeSource(responseLiveData);
                            if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
//                                将请求的参数返回 其包含所需要的参数
                                addGroupResult.setValue(addDeviceResult);
                            } else {
                                addDeviceResult.resultMsg = "场景创建成功，设备添加失败";
                                addGroupResult.setValue(addDeviceResult);
                            }
                        }
                    });
                } else {
                    addGroupResult.setValue(null);
                }
            }
        });
        return addGroupResult;

    }

    /**
     * 分两步
     * 1.创建情景
     * 2.添加设备到情景或添加场景到情景
     *
     * @param sceneRequest 添加情景的参数 含上传的device ids，上传之前就要判断是否存在
     * @return
     */
    public LiveData<SceneRequest> addScene(SceneRequest sceneRequest) {
        MediatorLiveData<SceneRequest> addSceneResult = new MediatorLiveData<>();
        ArrayMap<String, String> map = new ArrayMap<>();
        map.put("name", sceneRequest.name);
        map.put("meshId", getMeshId());
        RequestBody requestFile = RequestBody.create(RequestCreator.MEDIATYPE, sceneRequest.pic);
        MultipartBody.Part icon = MultipartBody.Part.createFormData("pic", sceneRequest.pic.getName(), requestFile);
        //先创建情景
        LiveData<ApiResponse<AddGroupSceneResult>> createGroupResponse = kimService.createScene(icon, map);
        addSceneResult.addSource(createGroupResponse, new Observer<ApiResponse<AddGroupSceneResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<AddGroupSceneResult> apiResponse) {
                addSceneResult.removeSource(createGroupResponse);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    AddGroupSceneResult addDeviceResult = apiResponse.body;
                    sceneRequest.sceneId = addDeviceResult.id;
                    sceneRequest.sceneAddress = addDeviceResult.sceneId;
                    //判断是添加设备还是添加场景
                    if (sceneRequest.isGroupSetting) {
                        addGroupToScene(addSceneResult, sceneRequest);
                    } else {
                        addLampsToScene(addSceneResult, sceneRequest);
                    }
                } else {
                    addSceneResult.setValue(null);
                }
            }
        });
        return addSceneResult;
    }


    //    添加场景到情景,成功后到设置页面
    private void addGroupToScene(MediatorLiveData<SceneRequest> addSceneResult, SceneRequest sceneRequest) {
        Log.d(TAG, "addGroupToScene: ");
        RequestBody requestBody = RequestCreator.requestAddGroupToScene(sceneRequest);
        //开始添加场景到情景
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addGroupToScene(requestBody);
        addSceneResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                addSceneResult.removeSource(responseLiveData);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
//                    getGroupFromScene(addSceneResult, sceneRequest);
                    addSceneResult.setValue(sceneRequest);
                } else {
                    addSceneResult.setValue(null);
                }
            }
        });
    }

    private void getGroupFromScene(MediatorLiveData<SceneRequest> addSceneResult, SceneRequest sceneRequest) {
        Log.d(TAG, "getGroupFromScene: ");
        RequestBody requestBody = RequestCreator.requestGroupFromScene(sceneRequest.sceneId);
        //开始添加场景到情景
        LiveData<ApiResponse<GroupList>> responseLiveData = kimService.getGroupsBySceneId(requestBody);
        addSceneResult.addSource(responseLiveData, new Observer<ApiResponse<GroupList>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<GroupList> apiResponse) {
                addSceneResult.removeSource(responseLiveData);
                if (apiResponse != null && apiResponse.isSuccessful()) {
                    //  将请求的参数返回 其包含所需要的参数
                    addSceneResult.setValue(sceneRequest);
                } else {
                    addSceneResult.setValue(null);
                }
            }
        });
    }

    //    添加灯具到情景
    private void addLampsToScene(MediatorLiveData<SceneRequest> addSceneResult, SceneRequest sceneRequest) {
        Log.d(TAG, "addLampsToScene: ");
        RequestBody requestBody = RequestCreator.requestAddLampToScene(sceneRequest);
        //开始添加设备到情景
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addDeviceToScene(requestBody);
        addSceneResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                addSceneResult.removeSource(responseLiveData);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    //  将请求的参数返回 其包含所需要的参数
                    addSceneResult.setValue(sceneRequest);
                } else {
                    addSceneResult.setValue(null);
                }
            }
        });
    }

    public LiveData<List<DeviceSetting>> getDeviceSetting(String sceneId) {
        Log.d(TAG, "getDeviceSetting: ");
        MediatorLiveData<List<DeviceSetting>> result = new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.requestDeviceSetting(sceneId);
        LiveData<ApiResponse<List<DeviceSetting>>> apiResponseLiveData = kimService.getDeviceSettingByObjectId(requestBody);
        result.addSource(apiResponseLiveData, new Observer<ApiResponse<List<DeviceSetting>>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<List<DeviceSetting>> listApiResponse) {
                result.removeSource(apiResponseLiveData);
                if (listApiResponse != null && listApiResponse.body != null) {
                    Log.d(TAG, "onChanged:getDeviceSetting ");
                    result.setValue(listApiResponse.body);
                } else {
                    result.setValue(null);
                }
            }
        });

        return result;
    }


    public LiveData<ApiResponse<List<DeviceSetting>>> getDeviceSetting2(String sceneId) {
        Log.d(TAG, "getDeviceSetting: ");
//        MediatorLiveData<List<DeviceSetting>> result=new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.requestDeviceSetting(sceneId);
        return kimService.getDeviceSettingByObjectId(requestBody);
        /*result.addSource(apiResponseLiveData, new Observer<ApiResponse<List<DeviceSetting>>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<List<DeviceSetting>> listApiResponse) {
                result.removeSource(apiResponseLiveData);
                if (listApiResponse!=null && listApiResponse.body != null) {
                    Log.d(TAG, "onChanged:getDeviceSetting ");
                    result.setValue(listApiResponse.body);
                }else {
                    result.setValue(null);
                }
            }
        });
*/
//        return result;
    }

    /**
     * @param clockRequest 添加闹钟的参数 含上传的device ids，上传之前就要判断是否存在
     * @return
     */
    public LiveData<ClockResult> addClock(ClockRequest clockRequest) {
        MediatorLiveData<ClockResult> clockResult = new MediatorLiveData<>();
        Map<String, String> map = new ArrayMap<>();
        map.put("name", clockRequest.name);
        map.put("type", String.valueOf(clockRequest.type));
        map.put("meshId", getMeshId());
        map.put("cycle", clockRequest.cycle);
//        RequestBody requestBody = RequestCreator.requestClock(clockRequest);
        LiveData<ApiResponse<ClockResult>> clock = kimService.createClock(map);
        clockResult.addSource(clock, new Observer<ApiResponse<ClockResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<ClockResult> apiResponse) {
                clockResult.removeSource(clock);
                //创建闹钟
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    ClockResult body = apiResponse.body;
//                    map.put("clockId", body.id);
                    clockRequest.clockId = body.id;
                    //开始添加设备到闹钟
                    RequestBody requestBody = RequestCreator.requestClock(clockRequest);
                    LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addDeviceToClock(requestBody);
                    clockResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
                        @Override
                        public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                            clockResult.removeSource(responseLiveData);
                            if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                                clockResult.setValue(body);
                            } else {
                                clockResult.setValue(null);
                            }
                        }
                    });
                } else {
                    clockResult.setValue(null);
                }
            }
        });
        return clockResult;

    }

    //更新闹钟
    @Deprecated
    public LiveData<RequestResult> updateClock(ClockRequest clockRequest) {
        MediatorLiveData<RequestResult> updateResult = new MediatorLiveData<>();
        Map<String, String> map = new ArrayMap<>();
        map.put("clockId", clockRequest.clockId);
        map.put("name", clockRequest.name);
        map.put("type", String.valueOf(clockRequest.type));
        map.put("meshId", getMeshId());
        map.put("cycle", clockRequest.cycle);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.updateClock(map);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    updateResult.setValue(apiResponse.body);
                } else {
                    updateResult.setValue(new RequestResult());
                }
            }
        });
        return updateResult;
    }

    /**
     * 更新闹钟和设备
     * 1.第一步更新
     * 2. 删除旧设备
     * 3.添加新设备
     *
     * @param clockRequest
     * @return
     */
    public LiveData<ClockRequest> updateClockAndDevice(ClockRequest clockRequest) {
        Log.d(TAG, "updateClockAndDevice: ");
        MediatorLiveData<ClockRequest> updateResult = new MediatorLiveData<>();
        Map<String, String> map = new ArrayMap<>();
        map.put("clockId", clockRequest.clockId);
        map.put("name", clockRequest.name);
        map.put("type", String.valueOf(clockRequest.type));
        map.put("meshId", getMeshId());
        map.put("cycle", clockRequest.cycle);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.updateClock(map);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    deleteClockDevice(updateResult, clockRequest);

                } else {
                    updateResult.setValue(null);
                }
            }
        });
        return updateResult;
    }


    private void deleteClockDevice(MediatorLiveData<ClockRequest> updateResult, ClockRequest clockRequest) {
        //使用全部灯具id 旧的不好记录
       /* Log.d(TAG, "onChanged: " + lampsObserver.toString());
        List<String> idsToDelete = new ArrayList<>();
        List<String> idsToAdd = new ArrayList<>();
        for (Lamp lamp : lampsObserver) {
            idsToDelete.add(lamp.getId());
            if (lamp.isSelected()) {
                idsToAdd.add(lamp.getId());
            }
        }*/
        clockRequest.deviceId = clockRequest.oldDeviceId;
        Log.d(TAG, "delete  " + clockRequest.deviceId);
        RequestBody requestDeleteClockDevice = RequestCreator.requestClock(clockRequest);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.deleteDeviceFromClock(requestDeleteClockDevice);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    clockRequest.deviceId = clockRequest.newDeviceId;
                    Log.d(TAG, "add " + clockRequest.deviceId);
                    addNewDeviceToClock(updateResult, clockRequest);
                } else {
                    updateResult.setValue(null);
                }
            }
        });
    }

    private void addNewDeviceToClock(MediatorLiveData<ClockRequest> updateResult, ClockRequest clockRequest) {
        RequestBody requestBody = RequestCreator.requestClock(clockRequest);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addDeviceToClock(requestBody);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    updateResult.setValue(clockRequest);
                } else {
                    updateResult.setValue(null);
                }
            }
        });
    }


    /**
     * 更新场景
     * 1.更新参数
     * 2.删除旧设备
     * 3.添加新设备
     *
     * @param groupRequest
     * @return
     */
    public LiveData<GroupRequest> updateGroupAndDevices(GroupRequest groupRequest) {
        MediatorLiveData<GroupRequest> updateResult = new MediatorLiveData<>();
        ArrayMap<String, String> map = new ArrayMap<>();
        map.put("name", groupRequest.name);
        map.put("groupId", groupRequest.groupId);
        map.put("meshId", getMeshId());
        LiveData<ApiResponse<AddGroupSceneResult>> responseLiveData;
        if (null != groupRequest.pic) {
            RequestBody requestFile = RequestBody.create(RequestCreator.MEDIATYPE, groupRequest.pic);
            MultipartBody.Part icon = MultipartBody.Part.createFormData("pic", groupRequest.pic.getName(), requestFile);
            responseLiveData = kimService.updateGroup(icon, map);
        } else {
            responseLiveData = kimService.updateGroup(map);
        }
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<AddGroupSceneResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<AddGroupSceneResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    //如果相等说明没有更新
                    if (groupRequest.oldDeviceId.equals(groupRequest.newDeviceId)) {
                        updateResult.setValue(groupRequest);
                    } else if (TextUtils.isEmpty(groupRequest.oldDeviceId)) {
                        //没有旧设备
                        groupRequest.deviceId = groupRequest.newDeviceId;
                        addNewDeviceToGroup(updateResult, groupRequest);
                    } else {
                        groupRequest.deviceId = groupRequest.oldDeviceId;
                        deleteGroupDevice(updateResult, groupRequest);
                    }
                } else {
                    updateResult.setValue(null);
                }
            }
        });
        return updateResult;
    }


    /**
     * 更新情景
     * 1.更新参数
     * 2.删除旧设备
     * 3.添加新设备
     *
     * @param sceneRequest
     * @return
     */
    public LiveData<SceneRequest> updateSceneAndDevices(SceneRequest sceneRequest) {
        MediatorLiveData<SceneRequest> updateResult = new MediatorLiveData<>();
        ArrayMap<String, String> map = new ArrayMap<>();
        map.put("name", sceneRequest.name);
        map.put("sceneId", sceneRequest.sceneId);
        map.put("meshId", getMeshId());
        LiveData<ApiResponse<AddGroupSceneResult>> responseLiveData;
        if (null != sceneRequest.pic) {
            RequestBody requestFile = RequestBody.create(RequestCreator.MEDIATYPE, sceneRequest.pic);
            MultipartBody.Part icon = MultipartBody.Part.createFormData("pic", sceneRequest.pic.getName(), requestFile);
            responseLiveData = kimService.updateScene(icon, map);
        } else {
            responseLiveData = kimService.updateScene(map);
        }
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<AddGroupSceneResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<AddGroupSceneResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    //如果相等说明没有更新
                    if (sceneRequest.oldDeviceId.equals(sceneRequest.newDeviceId)) {
                        updateResult.setValue(sceneRequest);
                    } else if (TextUtils.isEmpty(sceneRequest.oldDeviceId)) {
                        //没有旧设备
                        sceneRequest.deviceId = sceneRequest.newDeviceId;
                        addNewDeviceToScene(updateResult, sceneRequest);
                    } else {
//                        有旧设备先删除
                        sceneRequest.deviceId = sceneRequest.oldDeviceId;
                        deleteSceneDevice(updateResult, sceneRequest);
                    }
                } else {
                    updateResult.setValue(null);
                }
            }
        });
        return updateResult;
    }

    private void deleteGroupDevice(MediatorLiveData<GroupRequest> updateResult, GroupRequest groupRequest) {
        Log.d(TAG, "deleteGroupDevice:" + groupRequest);
        RequestBody requestBody = RequestCreator.requestDeleteDeviceInGroup(groupRequest.groupId, groupRequest.deviceId);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.deleteDeviceFromGroup(requestBody);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    groupRequest.deviceId = groupRequest.newDeviceId;
                    Log.d(TAG, "add " + groupRequest.deviceId);
                    addNewDeviceToGroup(updateResult, groupRequest);
                } else {
                    updateResult.setValue(null);
                }
            }
        });
    }

    private void deleteSceneDevice(MediatorLiveData<SceneRequest> updateResult, SceneRequest sceneRequest) {
        Log.d(TAG, "deleteSceneDevice:" + sceneRequest);
        RequestBody requestBody = RequestCreator.requestDeleteDeviceInScene(sceneRequest);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.deleteDeviceFromScene(requestBody);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    sceneRequest.deviceId = sceneRequest.newDeviceId;
                    Log.d(TAG, "add SceneDevice" + sceneRequest.deviceId);
                    addNewDeviceToScene(updateResult, sceneRequest);
                } else {
                    updateResult.setValue(null);
                }
            }
        });
    }

    private void addNewDeviceToScene(MediatorLiveData<SceneRequest> updateResult, SceneRequest sceneRequest) {
        Log.d(TAG, "addNewDeviceToScene: ");
        RequestBody requestBody = RequestCreator.requestAddLampToScene(sceneRequest);
        //开始添加设备到场景
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addDeviceToScene(requestBody);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    //     将请求的参数返回 其包含所需要的参数
                    updateResult.setValue(sceneRequest);
                } else {
                    updateResult.setValue(null);
                }
            }
        });
    }

    private void addNewDeviceToGroup(MediatorLiveData<GroupRequest> updateResult, GroupRequest groupRequest) {
        RequestBody requestBody = RequestCreator.requestAddLampToGroup(groupRequest);
        //开始添加设备到场景
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.addDeviceToGroup(requestBody);
        updateResult.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                updateResult.removeSource(responseLiveData);
                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    //     将请求的参数返回 其包含所需要的参数
                    updateResult.setValue(groupRequest);
                } else {
                    updateResult.setValue(null);
                }
            }
        });
    }


    public LiveData<List<Lamp>> getDevicesInGroup(boolean group, String id) {
        MediatorLiveData<List<Lamp>> lamps = new MediatorLiveData<>();
        RequestBody requestBody = group ? RequestCreator.requestGroupDevices(id) : RequestCreator.requestSceneDevices(id);
        LiveData<ApiResponse<GroupDevice>> devicesByGroupId = group ? kimService.getDevicesByGroupId(requestBody) : kimService.getDevicesBySceneId(requestBody);
        lamps.addSource(devicesByGroupId, new Observer<ApiResponse<GroupDevice>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                lamps.removeSource(devicesByGroupId);
                if (apiResponse != null && apiResponse.isSuccessful()) {
                    GroupDevice body = apiResponse.body;
                    List<Lamp> list = body.getList();
                    lamps.setValue(list);
                }
            }
        });
        return lamps;
    }


    public LiveData<List<Lamp>> getDevicesInClock(String id) {
        MediatorLiveData<List<Lamp>> lamps = new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.requestClockDevices(id);
        LiveData<ApiResponse<GroupDevice>> devices = kimService.getDevicesByClockId(requestBody);
        lamps.addSource(devices, new Observer<ApiResponse<GroupDevice>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                lamps.removeSource(devices);
                if (apiResponse != null && apiResponse.isSuccessful()) {
                    GroupDevice body = apiResponse.body;
                    List<Lamp> list = body.getList();
                    lamps.setValue(list);
                }
            }
        });
        return lamps;
    }

    /*获取情景景下的设备*/
    public LiveData<ApiResponse<GroupDevice>> getDevicesInScene(String sceneId) {
        RequestBody requestBody = RequestCreator.requestSceneDevices(sceneId);
        return kimService.getDevicesBySceneId(requestBody);
    }

    public LiveData<ApiResponse<RequestResult>> deleteGroup(String groupId) {
        RequestBody requestBody = RequestCreator.requestDeleteGroup(groupId);
        return kimService.deleteGroup(requestBody);
    }


    public LiveData<ApiResponse<RequestResult>> deleteScene2(String sceneId) {
        RequestBody requestBody = RequestCreator.requestDeleteScene(sceneId);
        return kimService.deleteScene(requestBody);
    }

    public LiveData<SceneRequest> deleteScene(SceneRequest sceneRequest) {
        MediatorLiveData<SceneRequest> result = new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.requestDeleteScene(sceneRequest.sceneId);
        LiveData<ApiResponse<RequestResult>> responseLiveData = kimService.deleteScene(requestBody);
        result.addSource(responseLiveData, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                result.removeSource(responseLiveData);
                if (null != apiResponse && apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    deleteSceneById(sceneRequest.sceneId);
                    result.setValue(sceneRequest);
                } else {
                    result.setValue(null);
                }
            }
        });

        return result;
    }


    public LiveData<ApiResponse<RequestResult>> deleteClock(String id) {
        RequestBody requestBody = RequestCreator.requestDeleteClock(id);
        return kimService.deleteClock(requestBody);
    }


    public LiveData<ApiResponse<RequestResult>> switchClock(Clock clock) {
        //1 开 0-关  使用1-当前状态 来切换
        RequestBody requestBody = RequestCreator.requestSwitchClock(clock.getId(), 1 - clock.getIsOpen());
        return kimService.switchClock(requestBody);
    }


    /**
     * 添加蓝牙网络,成功后其变为默认的，需要加载详情，如果失败返回没有意义
     * 最好是后台返回详细的数据
     *
     * @param reportMesh
     * @return
     */
    public LiveData<Resource<Boolean>> addMesh(ReportMesh reportMesh) {
        MediatorLiveData<Resource<Boolean>> result = new MediatorLiveData<>();
//        设置为加载状态
        result.setValue(Resource.loading(null));
        ArrayMap<String, String> map = new ArrayMap<>();
        map.put("homeName", reportMesh.homeName);
        map.put("meshName", reportMesh.meshName);
        map.put("meshPassword", reportMesh.meshPassword);
        map.put("othersId", getUserId());
//        map.put("othersId", profileObserver.getValue().userId);
        RequestBody requestFile = RequestBody.create(RequestCreator.MEDIATYPE, reportMesh.homeIcon);
        MultipartBody.Part icon = MultipartBody.Part.createFormData("homeIcon", reportMesh.homeIcon.getName(), requestFile);
        LiveData<ApiResponse<AddMeshResult>> apiResponseLiveData = kimService.reportBleMesh(icon, map);
        result.addSource(apiResponseLiveData, response -> {
//                监听是永久的，不会自动移除，一定要手动移除 防止重复监听
            result.removeSource(apiResponseLiveData);
//                Http请求是否成功, 返回数据是否正确
            if (response.isSuccessful() && response.body.succeed()) {
                loadDefaultMesh(result, response.body.meshId);

            } else {
                result.setValue(Resource.error(false, response.errorMsg));
            }
        });

        return result;

    }


    /**
     * mesh的修改
     *
     * @param reportMesh
     * @return
     */
    public LiveData<Resource<Boolean>> modifyMesh(ReportMesh reportMesh) {
        MediatorLiveData<Resource<Boolean>> result = new MediatorLiveData<>();
//        设置为加载状态
        result.setValue(Resource.loading(null));
        String meshId = getMeshId();
        ArrayMap<String, String> map = new ArrayMap<>();
        map.put("homeName", reportMesh.homeName);
        map.put("meshId", meshId);
        RequestBody requestFile = RequestBody.create(RequestCreator.MEDIATYPE, reportMesh.homeIcon);
        MultipartBody.Part icon = MultipartBody.Part.createFormData("homeIcon", reportMesh.homeIcon.getName(), requestFile);
        LiveData<ApiResponse<RequestResult>> apiResponseLiveData = kimService.upateMesh(icon, map);
        result.addSource(apiResponseLiveData, requestResultApiResponse -> {
//                监听是永久的，不会自动移除，一定要手动移除 防止重复监听
            result.removeSource(apiResponseLiveData);
            if (requestResultApiResponse.isSuccessful() && requestResultApiResponse.body.succeed()) {
                loadDefaultMesh(result, meshId);
            } else {
                result.setValue(Resource.error(false, requestResultApiResponse.errorMsg));
            }
        });

        return result;


    }


    //    在添加 更新 切换mesh都需要加载mesh详情
    public void loadDefaultMesh(MediatorLiveData<Resource<Boolean>> result, final String meshId) {
        RequestBody requestBody = RequestCreator.createMeshDetail(meshId);
        LiveData<ApiResponse<DefaultMesh>> apiResponseLiveData = kimService.meshDetail(requestBody);
        result.addSource(apiResponseLiveData, defaultMeshApiResponse -> {
            result.removeSource(apiResponseLiveData);
            if (defaultMeshApiResponse.isSuccessful()) {
                DefaultMesh defaultMesh = defaultMeshApiResponse.body;
                Profile profile = SmartLightApp.INSTANCE().getProfile();
                defaultMesh.isMine = defaultMesh.creater.equals(profile.userId);
                SmartLightApp.INSTANCE().setDefaultMesh(defaultMesh);
                SmartLightApp.INSTANCE().updateProfile(meshId);
                result.setValue(Resource.success(true, "获取蓝牙网络详情成功"));
            } else {
                result.setValue(Resource.error(false, "获取蓝牙网络详情失败"));

            }
        });

    }


    /**
     * 设置默认的mesh
     *
     * @return
     */
    public LiveData<Resource<Boolean>> setDefaultMesh(Mesh mesh) {
        String meshId = mesh.getId();
        MediatorLiveData<Resource<Boolean>> result = new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.createMeshDetail(meshId);
        result.setValue(Resource.loading(false));
        LiveData<ApiResponse<RequestResult>> response = kimService.setDefaultMesh(requestBody);
        result.addSource(response, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                result.removeSource(response);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    loadDefaultMesh(result, meshId);
                    /*if (apiResponse.body.succeed()) {
                        updateMeshId(meshId);
                        result.setValue(Resource.success(true, apiResponse.body.resultMsg));
                    } else {
                        result.setValue(Resource.error(false, apiResponse.body.resultMsg));
                    }*/
                } else {
                    result.setValue(Resource.error(false, apiResponse.errorMsg));
                }
            }
        });

        return result;
    }

    public LiveData<Resource<Boolean>> shareMesh(String input) {
        MediatorLiveData<Resource<Boolean>> result = new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.createShareMesh(input);
        result.setValue(Resource.loading(false));
        LiveData<ApiResponse<RequestResult>> response = kimService.shareMesh(requestBody);
        result.addSource(response, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                result.removeSource(response);
                if (apiResponse.isSuccessful()) {
                    if (apiResponse.body.succeed()) {
                        try {
                            JSONObject jsonObject = new JSONObject(input);
                            String meshId = jsonObject.optString("meshId", "");
                            loadDefaultMesh(result, meshId);
                        } catch (JSONException e) {
                            e.printStackTrace();
                            result.setValue(Resource.error(false, "获取meshId失败"));
                        }
                    } else {
                        result.setValue(Resource.error(false, apiResponse.body.resultMsg));
                    }

                } else {
                    result.setValue(Resource.error(false, apiResponse.errorMsg));
                }
            }
        });

        return result;

    }

    /**
     * 删除mesh  成功后需要删除本地数据
     * 删除默认的mesh该如何处理
     *
     * @return
     */
    public LiveData<Resource<Mesh>> deleteMesh(Mesh mesh) {
        MediatorLiveData<Resource<Mesh>> result = new MediatorLiveData<>();
        String meshId = mesh.getId();
        //首先看数据库是否有设备数据
        LiveData<List<Lamp>> local = lampDao.loadLampsUnderMesh(meshId);
        result.addSource(local, data -> {
            result.removeSource(local);
            if (data != null && !data.isEmpty() && mesh.isMyMesh()) {
                result.setValue(Resource.error(null, "有设备存在,请先删除设备"));
            } else {
                result.setValue(Resource.loading(null));
                RequestBody requestBody = RequestCreator.createDeleteMesh(meshId, mesh.getCreater());
                LiveData<ApiResponse<RequestResult>> response = kimService.deleteMesh(requestBody);
                result.addSource(response, new Observer<ApiResponse<RequestResult>>() {
                    @Override
                    public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                        result.removeSource(response);
                        if (apiResponse.isSuccessful()) {
                            if (apiResponse.body.succeed()) {
                                userDao.deleteMeshById(meshId);
                                result.setValue(Resource.success(mesh, apiResponse.body.resultMsg));
                            } else {
                                result.setValue(Resource.error(null, apiResponse.body.resultMsg));
                            }
                        } else {
                            result.setValue(Resource.error(null, apiResponse.errorMsg));
                        }
                    }
                });
            }
        });


        return result;
    }


    //    获取灯具列表
    public LiveData<Resource<List<Lamp>>> getLampList(int pageNo) {
        boolean shouldLoadFromRemote = true;
        MediatorLiveData<Resource<List<Lamp>>> result = new MediatorLiveData<>();
        result.setValue(Resource.loading(null));
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadLampsUnderMesh(meshId);
        if (shouldLoadFromRemote) {
            //先显示本地数据
            result.addSource(local, data -> {
                result.removeSource(local);
                result.setValue(Resource.loading(data));

            });
            RequestBody requestBody = RequestCreator.requestLampList(meshId, pageNo);
            LiveData<ApiResponse<LampList>> remote = kimService.deviceList(requestBody);
            result.addSource(remote, apiResponse -> {
                result.removeSource(remote);
                if (apiResponse.isSuccessful() && apiResponse.body != null && apiResponse.body.getList() != null) {
                    executors.diskIO().execute(() -> {
                        List<Lamp> list = apiResponse.body.getList();
                        if (list.isEmpty()) {
                            lampDao.deleteLampsUnderMesh(meshId);
                        } else {
                            for (Lamp lamp : list) {
                                lamp.setMeshId(meshId);
                            }
                            lampDao.insertLampsUnderMesh(list);
                        }
                        executors.mainThread().execute(() -> {
                            result.addSource(local, lamps -> {
//                                result.removeSource(local);
                                result.setValue(Resource.success(lamps, ""));
                            });
                        });
                    });
                } else {
                    result.setValue(Resource.error(null, "更新失败"));
                }
            });
        } else {
            result.addSource(local, lamps -> {
//                result.removeSource(local);
                result.setValue(Resource.success(lamps, ""));
            });
        }


        return result;
    }


    /**
     * @return
     */
    public LiveData<Resource<List<Lamp>>> getDeviceList() {
        MediatorLiveData<Resource<List<Lamp>>> result = new MediatorLiveData<>();
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadDevices(meshId);
        result.addSource(local, lamps -> result.setValue(Resource.success(lamps, "")));

        return result;

    }

    /**
     * @param selectedDeviceIdList 已选择的设备Id集合，Id为Mesh Address
     * @return
     */
    public LiveData<List<Lamp>> loadDeviceListWithMark(List<Integer> selectedDeviceIdList) {
        MediatorLiveData<List<Lamp>> mediatorLiveData = new MediatorLiveData<>();
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadDevices(meshId);
        mediatorLiveData.addSource(local, new Observer<List<Lamp>>() {
            @Override
            public void onChanged(List<Lamp> lamps) {
                mediatorLiveData.removeSource(local);
                if (lamps != null&&selectedDeviceIdList!=null) {
                    for (Lamp lamp : lamps) {
                        if (selectedDeviceIdList.contains(lamp.getDevice_id())) {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                        } else {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                        }
                    }
                }
                mediatorLiveData.setValue(lamps);
            }
        });

        return mediatorLiveData;
    }


    public LiveData<List<Lamp>> loadDeviceList(Predicate<Lamp> predicate) {
        MediatorLiveData<List<Lamp>> mediatorLiveData = new MediatorLiveData<>();
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadDevices(meshId);
        mediatorLiveData.addSource(local, new Observer<List<Lamp>>() {
            @Override
            public void onChanged(List<Lamp> lamps) {
                mediatorLiveData.removeSource(local);
                if (lamps != null&&predicate!=null) {
                    for (Lamp lamp : lamps) {
                        if (predicate.test(lamp)) {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                        } else {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                        }
                    }
                }
                mediatorLiveData.setValue(lamps);
            }
        });

        return mediatorLiveData;
    }


//    情景设置使用
    public LiveData<List<Lamp>> loadDeviceListWithStatus(final SparseIntArray setting) {
        MediatorLiveData<List<Lamp>> mediatorLiveData = new MediatorLiveData<>();
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadDevices(meshId);
        mediatorLiveData.addSource(local, new Observer<List<Lamp>>() {
            @Override
            public void onChanged(List<Lamp> lamps) {
                mediatorLiveData.removeSource(local);
                if (lamps != null) {
                    for (Lamp lamp : lamps) {
                        int brightness=setting.get(lamp.getDevice_id(), -100);
                        lamp.onOff.set(brightness > 0);
                        if (brightness!=-100) {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                        } else {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                        }
                    }
                }
                mediatorLiveData.setValue(lamps);
            }
        });

        return mediatorLiveData;
    }


    //加载场景下的设备 如果是修改标记已选择设备
    public LiveData<List<Lamp>> loadLampForGroup2(String groupId) {
        MediatorLiveData<List<Lamp>> data = new MediatorLiveData<>();
        String meshId = getMeshId();
//        String meshId = profileObserver.getValue().meshId;
        RequestBody requestBody = RequestCreator.requestLampList(meshId, 1);
        LiveData<ApiResponse<LampList>> apiResponseLiveData = kimService.deviceList(requestBody);
        data.addSource(apiResponseLiveData, new Observer<ApiResponse<LampList>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<LampList> lampListApiResponse) {
                data.removeSource(apiResponseLiveData);
                if (lampListApiResponse != null && lampListApiResponse.isSuccessful() && lampListApiResponse.body != null) {
                    List<Lamp> list = lampListApiResponse.body.getList();
                    //为空 说明是添加
                    if (TextUtils.isEmpty(groupId)) {
                        if (list != null) {
                            for (Lamp lamp : list) {
                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                            }
                            data.setValue(list);
                        } else {
                            data.setValue(null);
                        }
                    } else {
                        RequestBody requestBody = RequestCreator.requestGroupDevices(groupId);
                        LiveData<ApiResponse<GroupDevice>> devices = kimService.getDevicesByGroupId(requestBody);
                        data.addSource(devices, new Observer<ApiResponse<GroupDevice>>() {
                            @Override
                            public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                                data.removeSource(devices);
                                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body != null) {
                                    List<Lamp> selectedList = apiResponse.body.getList();
                                    //标记已选择数据
                                    if (selectedList != null) {
                                        for (Lamp lamp : list) {
                                            if (selectedList.contains(lamp)) {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                                            } else {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                                            }
                                        }
                                    }
                                }
                                data.setValue(list);
                            }
                        });
                    }
                }

            }
        });
        return data;

    }


    /**
     * 先从本地加载数据 如果为空则从网络加载，成功存入本地并继续执行操作A，失败返回null，如果本地不为空直接执行操作A
     * <p>
     * 操作A：
     *
     * @param groupId
     * @return
     */
    public LiveData<List<Lamp>> loadLampForGroup(String groupId) {
        MediatorLiveData<List<Lamp>> data = new MediatorLiveData<>();
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadDevices(meshId);
        data.addSource(local, new Observer<List<Lamp>>() {
            @Override
            public void onChanged(@Nullable List<Lamp> list) {
                data.removeSource(local);
                if (list == null || list.isEmpty()) {
                    Log.d(TAG, "local is empty");
                    RequestBody requestBody = RequestCreator.requestDeviceList(meshId, 1);
                    LiveData<ApiResponse<LampList>> apiResponseLiveData = kimService.deviceList(requestBody);
                    data.addSource(apiResponseLiveData, new Observer<ApiResponse<LampList>>() {
                        @Override
                        public void onChanged(@Nullable ApiResponse<LampList> apiResponse) {
                            if (apiResponse.isSuccessful() && apiResponse.body != null && apiResponse.body.getList() != null && !apiResponse.body.getList().isEmpty()) {
                                executors.diskIO().execute(() -> {
                                    List<Lamp> lamps = apiResponse.body.getList();
                                    for (Lamp lamp : lamps) {
                                        lamp.setMeshId(meshId);
                                    }
                                    lampDao.insertDevices(lamps);
                                    executors.mainThread().execute(() -> {
                                        LiveData<List<Lamp>> db = lampDao.loadDevices(meshId);
                                        data.addSource(db, devices -> {
                                            data.removeSource(local);
                                            loadGroupDevice(data, devices, groupId);
                                        });
                                    });
                                });
                            } else {
                                Log.d(TAG, "remote is empty");
                                data.setValue(null);
                            }
                        }
                    });

                } else {
                    loadGroupDevice(data, list, groupId);
                }

            }
        });
        return data;

    }

    //当Groupid不为空 说明是修改，加载场景已选的设备
    private void loadGroupDevice(MediatorLiveData<List<Lamp>> data, List<Lamp> list, String groupId) {
        if (TextUtils.isEmpty(groupId)) {
            for (Lamp lamp : list) {
                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
            }
            data.setValue(list);
        } else {
            RequestBody requestBody = RequestCreator.requestGroupDevices(groupId);
            LiveData<ApiResponse<GroupDevice>> devices = kimService.getDevicesByGroupId(requestBody);
            data.addSource(devices, new Observer<ApiResponse<GroupDevice>>() {
                @Override
                public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                    data.removeSource(devices);
                    if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body != null) {
                        List<Lamp> selectedList = apiResponse.body.getList();
                        //标记已选择数据
                        if (selectedList != null) {
                            for (Lamp lamp : list) {
                                if (selectedList.contains(lamp)) {
                                    lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                                } else {
                                    lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                                }
                            }
                        }
                    }
                    data.setValue(list);
                }
            });
        }
    }


    //加载情景景下的设备 如果是修改标记已选择设备
    public LiveData<List<Lamp>> loadLampForScene2(String sceneId) {
        MediatorLiveData<List<Lamp>> data = new MediatorLiveData<>();
        String meshId = getMeshId();
//        String meshId = profileObserver.getValue().meshId;
        RequestBody requestBody = RequestCreator.requestLampList(meshId, 1);
        LiveData<ApiResponse<LampList>> apiResponseLiveData = kimService.deviceList(requestBody);
        data.addSource(apiResponseLiveData, new Observer<ApiResponse<LampList>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<LampList> lampListApiResponse) {
                data.removeSource(apiResponseLiveData);
                if (lampListApiResponse != null && lampListApiResponse.isSuccessful() && lampListApiResponse.body != null) {
                    List<Lamp> list = lampListApiResponse.body.getList();
                    //为空 说明是添加
                    if (TextUtils.isEmpty(sceneId)) {
                        if (list != null) {
                            for (Lamp lamp : list) {
                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                            }
                            data.setValue(list);
                        } else {
                            data.setValue(null);
                        }
                    } else {
                        RequestBody requestBody = RequestCreator.requestSceneDevices(sceneId);
                        LiveData<ApiResponse<GroupDevice>> devices = kimService.getDevicesBySceneId(requestBody);
                        data.addSource(devices, new Observer<ApiResponse<GroupDevice>>() {
                            @Override
                            public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                                data.removeSource(devices);
                                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body != null) {
                                    List<Lamp> selectedList = apiResponse.body.getList();
                                    //标记已选择数据
                                    if (selectedList != null) {
                                        for (Lamp lamp : list) {
                                            if (selectedList.contains(lamp)) {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                                            } else {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                                            }
                                        }
                                    }
                                }
                                data.setValue(list);
                            }
                        });
                    }
                }

            }
        });
        return data;

    }


    public LiveData<List<Lamp>> loadLampForScene(String sceneId) {
        MediatorLiveData<List<Lamp>> data = new MediatorLiveData<>();
        String meshId = getMeshId();
        LiveData<List<Lamp>> local = lampDao.loadDevices(meshId);

//        RequestBody requestBody = RequestCreator.requestLampList(meshId, 1);
//        LiveData<ApiResponse<LampList>> apiResponseLiveData = kimService.deviceList(requestBody);
        data.addSource(local, new Observer<List<Lamp>>() {
            @Override
            public void onChanged(@Nullable List<Lamp> list) {
                data.removeSource(local);
                if (list != null && !list.isEmpty()) {
                    //为空 说明是添加
                    if (TextUtils.isEmpty(sceneId)) {
                        for (Lamp lamp : list) {
                            lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                        }
                        data.setValue(list);
                    } else {
                        RequestBody requestBody = RequestCreator.requestSceneDevices(sceneId);
                        LiveData<ApiResponse<GroupDevice>> devices = kimService.getDevicesBySceneId(requestBody);
                        data.addSource(devices, new Observer<ApiResponse<GroupDevice>>() {
                            @Override
                            public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                                data.removeSource(devices);
                                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body != null) {
                                    List<Lamp> selectedList = apiResponse.body.getList();
                                    //标记已选择数据
                                    if (selectedList != null) {
                                        for (Lamp lamp : list) {
                                            if (selectedList.contains(lamp)) {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                                            } else {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                                            }
                                        }
                                    }
                                }
                                data.setValue(list);
                            }
                        });
                    }
                } else {
                    data.setValue(null);
                }

            }
        });
        return data;

    }

    /**
     * 为闹钟模块加载灯具
     *
     * @param clockId 如果为空 怎加载当前mesh下的灯具，不为空还要加载此clock下的灯具 供用户在两个页面选择
     * @return
     */
    public LiveData<List<Lamp>> loadLampsForClock(String clockId) {
        Log.d(TAG, "loadLampsForClock() called with: clockId = [" + clockId + "]");
        MediatorLiveData<List<Lamp>> data = new MediatorLiveData<>();
        String meshId = getMeshId();
//        String meshId = profileObserver.getValue().meshId;
        RequestBody requestBody = RequestCreator.requestLampList(meshId, 1);
        LiveData<ApiResponse<LampList>> apiResponseLiveData = kimService.deviceList(requestBody);
        data.addSource(apiResponseLiveData, new Observer<ApiResponse<LampList>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<LampList> lampListApiResponse) {
                data.removeSource(apiResponseLiveData);
                if (lampListApiResponse != null && lampListApiResponse.isSuccessful() && lampListApiResponse.body != null) {
                    List<Lamp> list = lampListApiResponse.body.getList();
                    //为空 说明是添加
                    if (TextUtils.isEmpty(clockId)) {
                        if (list != null) {
                            for (Lamp lamp : list) {
                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                            }
                            data.setValue(list);
                        } else {
                            data.setValue(null);
                        }
                    } else {
                        RequestBody requestBody = RequestCreator.requestClockDevices(clockId);
                        LiveData<ApiResponse<GroupDevice>> devices = kimService.getDevicesByClockId(requestBody);
                        data.addSource(devices, new Observer<ApiResponse<GroupDevice>>() {
                            @Override
                            public void onChanged(@Nullable ApiResponse<GroupDevice> apiResponse) {
                                data.removeSource(devices);
                                if (apiResponse != null && apiResponse.isSuccessful() && apiResponse.body != null) {
                                    List<Lamp> selectedList = apiResponse.body.getList();
                                    //标记已选择数据
                                    if (selectedList != null) {
                                        for (Lamp lamp : list) {
                                            if (selectedList.contains(lamp)) {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_SELECTED);
                                            } else {
                                                lamp.lampStatus.set(BindingAdapters.LIGHT_HIDE);
                                            }
                                        }
                                    }
                                }
                                data.setValue(list);
                            }
                        });
                    }
                }

            }
        });
        return data;

    }


    //获取hub列表
    public LiveData<ApiResponse<HubList>> getHubList(int pageNo) {
        String meshId = getMeshId();
        RequestBody requestBody = RequestCreator.requestHubList(meshId, pageNo);
        return kimService.hubList(requestBody);
    }

    //获取场景列表
    public LiveData<ApiResponse<GroupList>> getGroupList(int pageNo) {
        String meshId = getMeshId();
        RequestBody requestBody = RequestCreator.requestHubList(meshId, pageNo);
        return kimService.groupList(requestBody);
    }


    public LiveData<ApiResponse<SceneList>> getSceneList2(int pageNo) {
        String meshId = getMeshId();
        RequestBody requestBody = RequestCreator.requestHubList(meshId, pageNo);
        return kimService.getSceneList(requestBody);
    }

    public LiveData<Resource<List<Scene>>> getSceneList(int pageNo) {
        String meshId = getMeshId();
        return new NetWorkBoundResource<SceneList, List<Scene>>(executors) {

            @Override
            protected void saveResult(SceneList body) {
                List<Scene> list = body.getList();
                if (list != null && !list.isEmpty()) {
                    userDao.insertScene(list);
                } else {
                    userDao.deleteScenes(meshId);
                }
            }

            @Override
            protected void onLoadFail() {

            }

            @Override
            protected LiveData<ApiResponse<SceneList>> createCall() {
                RequestBody requestBody = RequestCreator.requestHubList(meshId, pageNo);
                return kimService.getSceneList(requestBody);
            }

            @Override
            protected boolean shouldLoadFromRemote(List<Scene> scenes) {
                return true;
            }

            @NonNull
            @Override
            protected LiveData<List<Scene>> loadFromLocal() {
                return userDao.loadScene(meshId);
            }
        }.asLiveData();
    }

    public LiveData<ApiResponse<ClockList>> getClockList(int pageNo) {
        String meshId = getMeshId();
        RequestBody requestBody = RequestCreator.requestHubList(meshId, pageNo);
        return kimService.getClockList(requestBody);
    }


    public LiveData<ApiResponse<RequestResult>> reportDevice(Map<String, String> map) {
        String meshId = getMeshId();
        map.put("meshId", meshId);
//        map.put("meshId", defaultMeshObserver.getValue().id);
        RequestBody requestBody = RequestCreator.requestAdLamp(map);
        return kimService.reportDevice(requestBody);
    }


    //删除灯具
    public LiveData<Resource<Lamp>> deleteDevice(Lamp lamp) {
        MediatorLiveData<Resource<Lamp>> result = new MediatorLiveData<>();
        result.setValue(Resource.loading(null));
        RequestBody requestBody = RequestCreator.requestDeleteLamp(lamp.getId());
        LiveData<ApiResponse<RequestResult>> response = kimService.deleteDevice(requestBody);
        result.addSource(response, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                result.removeSource(response);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    lampDao.deleteLampById(lamp.getId());
                    result.setValue(Resource.success(lamp, apiResponse.body.resultMsg));
                } else {
                    result.setValue(Resource.error(null, "删除失败"));
                }
            }
        });

        return result;
    }


    public LiveData<Resource<Hub>> deleteHub(Hub hub) {
        MediatorLiveData<Resource<Hub>> result = new MediatorLiveData<>();
        result.setValue(Resource.loading(null));
        RequestBody requestBody = RequestCreator.requestDeleteHub(hub.getId());
        LiveData<ApiResponse<RequestResult>> response = kimService.deleteHub(requestBody);
        result.addSource(response, new Observer<ApiResponse<RequestResult>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<RequestResult> apiResponse) {
                result.removeSource(response);
                if (apiResponse.isSuccessful() && apiResponse.body.succeed()) {
                    result.setValue(Resource.success(hub, ""));
                } else {
                    result.setValue(Resource.error(null, "删除失败"));
                }
            }
        });

        return result;
    }

    public LiveData<ApiResponse<RequestResult>> reportHub(AddHubRequest addHubRequest) {
//        DefaultMesh defaultMesh = defaultMeshObserver.getValue();
        DefaultMesh defaultMesh = SmartLightApp.INSTANCE().getDefaultMesh();
        addHubRequest.meshId = defaultMesh.id;
        addHubRequest.meshName = defaultMesh.aijiaName;
        addHubRequest.password = defaultMesh.password;
        RequestBody requestBody = RequestCreator.createAddHub(addHubRequest);
        return kimService.reportHub(requestBody);
    }

    public LiveData<ApiResponse<RequestResult>> getDeviceId() {
//        DefaultMesh defaultMesh = defaultMeshObserver.getValue();
//        RequestBody requestBody = RequestCreator.requestDeviceId(defaultMesh.id);
        String meshId = getMeshId();
        RequestBody requestBody = RequestCreator.requestDeviceId(meshId);
//        Log.d(TAG, "getDeviceId: " + defaultMesh.id);
        return kimService.getDeviceId(requestBody);

    }


    /**
     * 先从本地获取，再判断是否需要从网络获取 ，从网络获取失败使用本地数据，否则使用网络数据
     * <p>
     * 一次性请求足够多的 不再分页 没有必要
     *
     * @return mesh列表  找到默认的 需要显示在首页
     */
    public LiveData<Resource<List<Mesh>>> loadMeshList() {
        boolean needLoadFromLocal = true;
        MediatorLiveData<Resource<List<Mesh>>> result = new MediatorLiveData<>();
        String userId = getUserId();
//        String userId=profileObserver.getValue().userId;
        result.setValue(Resource.loading(null));
//        加载本地
        LiveData<List<Mesh>> dbSource = userDao.loadAllMesh();
//        监听本地加载结果
        result.addSource(dbSource, meshes -> {
            result.removeSource(dbSource);
//                如果需要从网络获取
            if (needLoadFromLocal) {
                //先显示本地内容和加载状态
                result.addSource(dbSource, newData -> result.setValue(Resource.loading(newData)));

                RequestBody requestBody = RequestCreator.createPage(1, 40);
                LiveData<ApiResponse<MeshList>> apiResponse = kimService.meshList(requestBody);
//                监听网络请求
                result.addSource(apiResponse, response -> {
                    result.removeSource(dbSource);
                    result.removeSource(apiResponse);
                    //加载成功
                    if (response.isSuccessful()) {
//                            在子线程中保存到本地
                        executors.diskIO().execute(() -> {
                            List<Mesh> list = response.body.getList();
                            if (list != null && !list.isEmpty()) {
                                for (Mesh mesh : list) {
                                    mesh.setUserId(userId);
                                }
                                userDao.insertMeshes(list);
                            } else {
                                userDao.deleteAllMeshes();
                            }
//                            在主线程中从数据库获取
                            executors.mainThread().execute(() -> {
                                LiveData<List<Mesh>> local = userDao.loadAllMesh();
                                result.addSource(local, newData -> {
                                    //如果还想监听数据库变化 可以不用移除
                                    result.removeSource(local);
                                    result.setValue(Resource.success(newData, ""));
                                });
                            });
                        });

                    } else {
                        result.addSource(dbSource, newData -> result.setValue(Resource.error(newData, response.errorMsg)));
                    }
                });
            } else {
                result.addSource(dbSource, newData -> result.setValue(Resource.success(newData, "")));
            }
        });

        return result;
    }

    public LiveData<List<Mesh>> loadMyMeshFromLocal() {
        MediatorLiveData<List<Mesh>> result = new MediatorLiveData<>();
        RequestBody requestBody = RequestCreator.createPage(1, 40);
        LiveData<ApiResponse<MeshList>> apiResponse = kimService.meshList(requestBody);
        result.addSource(apiResponse, new Observer<ApiResponse<MeshList>>() {
            @Override
            public void onChanged(@Nullable ApiResponse<MeshList> response) {
                result.removeSource(apiResponse);
                if (response.isSuccessful()) {
                    List<Mesh> list = response.body.getList();
                    if (list != null && !list.isEmpty()) {
                        userDao.insertMeshes(list);
                    } else {
                        userDao.deleteAllMeshes();
                    }
//                            在主线程中从数据库获取
                    executors.mainThread().execute(() -> {
                        String userId = getUserId();
//                        String userId = profileObserver.getValue().userId;
                        LiveData<List<Mesh>> local = userDao.loadMyMesh(userId);
                        result.addSource(local, newData -> {
                            //如果还想监听数据库变化 可以不用移除
                            result.removeSource(local);
                            result.setValue(newData);
                        });
                    });

                }

            }
        });
        return result;

    }

    /**
     * 更新单个设备状态
     *
     * @param brightness 亮度
     * @param deviceId   具体设备id
     */
    public void updateDeviceStatus(int brightness, int deviceId) {
        String meshId = getMeshId();
        lampDao.updateDeviceStatus(brightness, meshId, deviceId);

    }

    /**
     * 更新整个mesh下灯具状态 通常是离线
     *
     * @param brightness <0
     */
    public void updateMeshStatus(int brightness) {
        String meshId = getMeshId();
        lampDao.updateMeshStatus(brightness, meshId);
    }


    //批量更新设备状态
    public void updateDevicesStatus(List<OnlineStatusNotificationParser.DeviceNotificationInfo> notificationInfoList) {
        mDataBase.runInTransaction(() -> {
            String meshId = getMeshId();
            for (OnlineStatusNotificationParser.DeviceNotificationInfo notificationInfo : notificationInfoList) {
                int meshAddress = notificationInfo.meshAddress;
                int brightness = notificationInfo.brightness;
                Log.d(TAG, "updateDevicesStatus: ");
                lampDao.updateDeviceStatus(brightness, meshId, meshAddress);
            }
        });
    }

    public void deleteSceneById(String id) {
        userDao.deleteSceneById(id);
    }


    public void insertDevice(Lamp lamp) {
        lampDao.insertDevice(lamp);
    }

    public void deleteDeviceFromLocal(Lamp lamp) {
        lampDao.deleteLampById(lamp.getId());
    }


    public int getGroupNum() {
        return groupDao.getGroupRowId();
    }

    public void addUpdateGroup(Group group) {
        groupDao.insert(group);
    }

    public LiveData<List<Group>> getGroupList() {
        return groupDao.loadAllGroups();
    }

    public void deleteGroup(Group group) {
        groupDao.deleteGroup(group);
    }


    public int getMaxSceneId() {
        return sceneDao.getMaxSceneId();
    }

    public void addUpdateScene(Scene scene) {
        sceneDao.insert(scene);
    }

    public LiveData<List<Scene>> getSceneList() {
        return sceneDao.loadAllScene();
    }

    public void deleteScene(Scene scene) {
        sceneDao.delete(scene);
    }

    public void addUpdateClock(Clock clock) {
        clockDao.insert(clock);
    }

    public LiveData<List<Clock>> getClockList() {
        return clockDao.loadAllClock();
    }

    public void deleteClock(Clock clock) {
        clockDao.delete(clock);
    }
}
