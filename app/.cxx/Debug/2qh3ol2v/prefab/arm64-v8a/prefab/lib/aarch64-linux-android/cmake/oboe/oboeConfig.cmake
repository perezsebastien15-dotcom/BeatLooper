if(NOT TARGET oboe::oboe)
add_library(oboe::oboe SHARED IMPORTED)
set_target_properties(oboe::oboe PROPERTIES
    IMPORTED_LOCATION "C:/Users/seperez1/.gradle/caches/transforms-3/775df61766df2c234b1fd2f46c7246e5/transformed/oboe-1.8.0/prefab/modules/oboe/libs/android.arm64-v8a/liboboe.so"
    INTERFACE_INCLUDE_DIRECTORIES "C:/Users/seperez1/.gradle/caches/transforms-3/775df61766df2c234b1fd2f46c7246e5/transformed/oboe-1.8.0/prefab/modules/oboe/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

