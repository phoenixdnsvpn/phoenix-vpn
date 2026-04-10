set(CMAKE_C_COMPILER "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang")
set(CMAKE_C_COMPILER_ARG1 "")
set(CMAKE_C_COMPILER_ID "Clang")
set(CMAKE_C_COMPILER_VERSION "19.0.1")
set(CMAKE_C_COMPILER_VERSION_INTERNAL "")
set(CMAKE_C_COMPILER_WRAPPER "")
set(CMAKE_C_STANDARD_COMPUTED_DEFAULT "17")
set(CMAKE_C_EXTENSIONS_COMPUTED_DEFAULT "ON")
set(CMAKE_C_COMPILE_FEATURES "c_std_90;c_function_prototypes;c_std_99;c_restrict;c_variadic_macros;c_std_11;c_static_assert;c_std_17;c_std_23")
set(CMAKE_C90_COMPILE_FEATURES "c_std_90;c_function_prototypes")
set(CMAKE_C99_COMPILE_FEATURES "c_std_99;c_restrict;c_variadic_macros")
set(CMAKE_C11_COMPILE_FEATURES "c_std_11;c_static_assert")
set(CMAKE_C17_COMPILE_FEATURES "c_std_17")
set(CMAKE_C23_COMPILE_FEATURES "c_std_23")

set(CMAKE_C_PLATFORM_ID "Linux")
set(CMAKE_C_SIMULATE_ID "")
set(CMAKE_C_COMPILER_FRONTEND_VARIANT "GNU")
set(CMAKE_C_SIMULATE_VERSION "")




set(CMAKE_AR "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar")
set(CMAKE_C_COMPILER_AR "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar")
set(CMAKE_RANLIB "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib")
set(CMAKE_C_COMPILER_RANLIB "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib")
set(CMAKE_LINKER "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/ld.lld")
set(CMAKE_MT "")
set(CMAKE_COMPILER_IS_GNUCC )
set(CMAKE_C_COMPILER_LOADED 1)
set(CMAKE_C_COMPILER_WORKS TRUE)
set(CMAKE_C_ABI_COMPILED TRUE)

set(CMAKE_C_COMPILER_ENV_VAR "CC")

set(CMAKE_C_COMPILER_ID_RUN 1)
set(CMAKE_C_SOURCE_FILE_EXTENSIONS c;m)
set(CMAKE_C_IGNORE_EXTENSIONS h;H;o;O;obj;OBJ;def;DEF;rc;RC)
set(CMAKE_C_LINKER_PREFERENCE 10)

# Save compiler ABI information.
set(CMAKE_C_SIZEOF_DATA_PTR "8")
set(CMAKE_C_COMPILER_ABI "ELF")
set(CMAKE_C_BYTE_ORDER "LITTLE_ENDIAN")
set(CMAKE_C_LIBRARY_ARCHITECTURE "")

if(CMAKE_C_SIZEOF_DATA_PTR)
  set(CMAKE_SIZEOF_VOID_P "${CMAKE_C_SIZEOF_DATA_PTR}")
endif()

if(CMAKE_C_COMPILER_ABI)
  set(CMAKE_INTERNAL_PLATFORM_ABI "${CMAKE_C_COMPILER_ABI}")
endif()

if(CMAKE_C_LIBRARY_ARCHITECTURE)
  set(CMAKE_LIBRARY_ARCHITECTURE "")
endif()

set(CMAKE_C_CL_SHOWINCLUDES_PREFIX "")
if(CMAKE_C_CL_SHOWINCLUDES_PREFIX)
  set(CMAKE_CL_SHOWINCLUDES_PREFIX "${CMAKE_C_CL_SHOWINCLUDES_PREFIX}")
endif()





set(CMAKE_C_IMPLICIT_INCLUDE_DIRECTORIES "/opt/intel/oneapi/tbb/2021.9.0/include;/opt/intel/oneapi/mpi/2021.9.0/include;/opt/intel/oneapi/mkl/2023.1.0/include;/opt/intel/oneapi/ipp/2021.8.0/include;/opt/intel/oneapi/ippcp/2021.7.0/include;/opt/intel/oneapi/dpl/2022.1.0/linux/include;/opt/intel/oneapi/dpcpp-ct/2023.1.0/include;/opt/intel/oneapi/dnnl/2023.1.0/cpu_dpcpp_gpu_dpcpp/include;/opt/intel/oneapi/dev-utilities/2021.9.0/include;/opt/intel/oneapi/dal/2023.1.0/include;/opt/intel/oneapi/ccl/2021.9.0/include/cpu_gpu_dpcpp;/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/19/include;/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/x86_64-linux-android;/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include")
set(CMAKE_C_IMPLICIT_LINK_LIBRARIES "-l:libunwind.a;dl;c;-l:libunwind.a;dl")
set(CMAKE_C_IMPLICIT_LINK_DIRECTORIES "/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/19/lib/linux/x86_64;/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/24;/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android;/home/mark/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib;/opt/intel/oneapi/tbb/2021.9.0/lib/intel64/gcc4.8;/opt/intel/oneapi/mpi/2021.9.0/libfabric/lib;/opt/intel/oneapi/mpi/2021.9.0/lib/release;/opt/intel/oneapi/mpi/2021.9.0/lib;/opt/intel/oneapi/mkl/2023.1.0/lib/intel64;/opt/intel/oneapi/ipp/2021.8.0/lib/intel64;/opt/intel/oneapi/ippcp/2021.7.0/lib/intel64;/opt/intel/oneapi/dnnl/2023.1.0/cpu_dpcpp_gpu_dpcpp/lib;/opt/intel/oneapi/dal/2023.1.0/lib/intel64;/opt/intel/oneapi/compiler/2023.1.0/linux/compiler/lib/intel64_lin;/opt/intel/oneapi/compiler/2023.1.0/linux/lib;/opt/intel/oneapi/clck/2021.7.3/lib/intel64;/opt/intel/oneapi/ccl/2021.9.0/lib/cpu_gpu_dpcpp")
set(CMAKE_C_IMPLICIT_LINK_FRAMEWORK_DIRECTORIES "")
