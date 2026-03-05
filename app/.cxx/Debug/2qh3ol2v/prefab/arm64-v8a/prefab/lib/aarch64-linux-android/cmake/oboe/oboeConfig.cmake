if(NOT TARGET oboe::oboe)
add_library(oboe::oboe SHARED IMPORTED)
set_target_properties(oboe::oboe PROPERTIES
    IMPORTED_LOCATION "C:/Users/seperez1/.gradle/caches/transforms-3/8ea5b5a13cb34a46b429b09026a3ae16/transformed/oboe-1.10.0/prefab/modules/oboe/libs/android.arm64-v8a/liboboe.so"
    INTERFACE_INCLUDE_DIRECTORIES "C:/Users/seperez1/.gradle/caches/transforms-3/8ea5b5a13cb34a46b429b09026a3ae16/transformed/oboe-1.10.0/prefab/modules/oboe/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

