#!/usr/bin/env python3
"""Generate ios/QiYan.xcodeproj.

The app target uses file-system-synchronized groups (Xcode 16+, objectVersion 77):
everything under ios/QiYan/ and ios/Core/ is picked up automatically (Swift and C to
Sources, lproj tables / Assets.xcassets / data to Resources). Nothing to list by hand.

Native code that is not built by Xcode:
  Vendor/pikafish/<sdk>/libpikafish.a     tools/build_pikafish.sh (PikaFish + Engine/pikafish_bridge.cpp)
  Vendor/onnxruntime.xcframework          tools/fetch_onnxruntime.sh
Both are linked through per-SDK search paths, so the project file never names them.

Run from anywhere:  python3 ios/tools/gen_xcodeproj.py
"""
import os

IOS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJ = os.path.join(IOS, "QiYan.xcodeproj")
APP = "QiYan"
UI = "QiYanUITests"

BUNDLE_ID = "com.zhangfeiyang.qiyan"         # com.zhongguoxiangqi (Android) is taken on the App Store
TEAM_ID = "LSAZF25AG8"
MARKETING_VERSION = "1.0"
BUILD_NUMBER = "3"                     # bump for every App Store Connect upload
DEPLOYMENT_TARGET = "17.0"

REGIONS = ["en", "Base", "zh-Hans", "zh-Hant", "ja", "ko", "es", "pt-BR", "fr", "de", "it",
           "ru", "tr", "ar", "id", "th", "vi"]

ids = {}


def uid(tag):
    if tag not in ids:
        ids[tag] = "C17E" + format(len(ids) + 1, "020X")
    return ids[tag]


def q(s):
    return '"' + s.replace('"', '\\"') + '"'


def settings_block(d, indent):
    pad = "\t" * indent
    lines = []
    for k in sorted(d):
        v = d[k]
        key = q(k) if "[" in k else k
        if isinstance(v, list):
            inner = "".join(f"{pad}\t{x},\n" for x in v)
            lines.append(f"{pad}{key} = (\n{inner}{pad});")
        else:
            lines.append(f"{pad}{key} = {v};")
    return "\n".join(lines)


def main():
    app_ref, ui_ref = uid("app.ref"), uid("ui.ref")
    app_group, core_group, ui_group = uid("group.app"), uid("group.core"), uid("group.ui")
    main_group, products = uid("group.main"), uid("group.products")
    target, ui_target, project = uid("target"), uid("ui.target"), uid("project")
    p_sources, p_frameworks, p_resources = uid("phase.sources"), uid("phase.frameworks"), uid("phase.resources")
    u_sources, u_frameworks, u_resources = uid("ui.phase.sources"), uid("ui.phase.frameworks"), uid("ui.phase.resources")
    proxy, dep = uid("ui.proxy"), uid("ui.dependency")
    proj_list, target_list, ui_list = uid("cfglist.project"), uid("cfglist.target"), uid("cfglist.ui")
    proj_debug, proj_release = uid("cfg.project.debug"), uid("cfg.project.release")
    tgt_debug, tgt_release = uid("cfg.target.debug"), uid("cfg.target.release")
    ui_debug, ui_release = uid("cfg.ui.debug"), uid("cfg.ui.release")

    project_common = {
        "ALWAYS_SEARCH_USER_PATHS": "NO",
        "CLANG_ENABLE_MODULES": "YES",
        "CLANG_ENABLE_OBJC_ARC": "YES",
        "CLANG_CXX_LANGUAGE_STANDARD": q("c++17"),
        "ENABLE_STRICT_OBJC_MSGSEND": "YES",
        "GCC_NO_COMMON_BLOCKS": "YES",
        "IPHONEOS_DEPLOYMENT_TARGET": DEPLOYMENT_TARGET,
        "SDKROOT": "iphoneos",
        "SWIFT_VERSION": "5.0",
        "LOCALIZATION_PREFERS_STRING_CATALOGS": "NO",
    }
    proj_debug_s = dict(project_common, **{
        "DEBUG_INFORMATION_FORMAT": "dwarf",
        "ENABLE_TESTABILITY": "YES",
        "GCC_OPTIMIZATION_LEVEL": "0",
        "ONLY_ACTIVE_ARCH": "YES",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": q("DEBUG $(inherited)"),
        "SWIFT_OPTIMIZATION_LEVEL": q("-Onone"),
    })
    proj_release_s = dict(project_common, **{
        "DEBUG_INFORMATION_FORMAT": q("dwarf-with-dsym"),
        "ENABLE_NS_ASSERTIONS": "NO",
        "SWIFT_COMPILATION_MODE": "wholemodule",
        "SWIFT_OPTIMIZATION_LEVEL": q("-O"),
        "VALIDATE_PRODUCT": "YES",
    })
    target_common = {
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
        "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME": "AccentColor",
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": BUILD_NUMBER,
        "DEVELOPMENT_TEAM": TEAM_ID,
        "ENABLE_PREVIEWS": "YES",
        "GENERATE_INFOPLIST_FILE": "NO",
        "INFOPLIST_FILE": "Config/Info.plist",
        "LD_RUNPATH_SEARCH_PATHS": ["\"$(inherited)\"", "\"@executable_path/Frameworks\""],
        "MARKETING_VERSION": MARKETING_VERSION,
        "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE_ID,
        "PRODUCT_NAME": q("$(TARGET_NAME)"),
        "SUPPORTED_PLATFORMS": q("iphoneos iphonesimulator"),
        "SUPPORTS_MACCATALYST": "NO",
        "SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD": "NO",
        "SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD": "NO",
        "SWIFT_EMIT_LOC_STRINGS": "NO",
        "TARGETED_DEVICE_FAMILY": "1",
        "SWIFT_OBJC_BRIDGING_HEADER": q("QiYan/Bridge/QiYan-Bridging-Header.h"),
        "HEADER_SEARCH_PATHS": ["\"$(inherited)\"", "\"$(PROJECT_DIR)/Engine\"", "\"$(PROJECT_DIR)/QiYan/Vision\""],
        "LIBRARY_SEARCH_PATHS[sdk=iphoneos*]": ["\"$(inherited)\"", "\"$(PROJECT_DIR)/Vendor/pikafish/iphoneos\""],
        "LIBRARY_SEARCH_PATHS[sdk=iphonesimulator*]": ["\"$(inherited)\"", "\"$(PROJECT_DIR)/Vendor/pikafish/iphonesimulator\""],
        "FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]": ["\"$(inherited)\"", "\"$(PROJECT_DIR)/Vendor/onnxruntime.xcframework/ios-arm64\""],
        "FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]": ["\"$(inherited)\"",
                                                         "\"$(PROJECT_DIR)/Vendor/onnxruntime.xcframework/ios-arm64_x86_64-simulator\""],
        "OTHER_LDFLAGS": ["\"$(inherited)\"", "\"-lpikafish\"", "\"-lc++\"", "\"-framework\"", "onnxruntime",
                          "\"-framework\"", "CoreML", "\"-framework\"", "Accelerate"],
        # libpikafish.a for the simulator is arm64 only
        "EXCLUDED_ARCHS[sdk=iphonesimulator*]": "x86_64",
    }
    ui_common = {
        "CODE_SIGN_STYLE": "Automatic",
        "CURRENT_PROJECT_VERSION": BUILD_NUMBER,
        "DEVELOPMENT_TEAM": TEAM_ID,
        "GENERATE_INFOPLIST_FILE": "YES",
        "MARKETING_VERSION": MARKETING_VERSION,
        "PRODUCT_BUNDLE_IDENTIFIER": BUNDLE_ID + ".uitests",
        "PRODUCT_NAME": q("$(TARGET_NAME)"),
        "SUPPORTED_PLATFORMS": q("iphoneos iphonesimulator"),
        "SWIFT_EMIT_LOC_STRINGS": "NO",
        "TARGETED_DEVICE_FAMILY": "1",
        "TEST_TARGET_NAME": APP,
        "EXCLUDED_ARCHS[sdk=iphonesimulator*]": "x86_64",
    }

    def phase(u, isa, name):
        return (f"\t\t{u} /* {name} */ = {{\n\t\t\tisa = {isa};\n\t\t\tbuildActionMask = 2147483647;\n"
                f"\t\t\tfiles = (\n\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t}};")

    def native(u, name, cfgs, phases, groups, ref, ptype, deps=""):
        ph = "".join(f"\t\t\t\t{x},\n" for x in phases)
        gs = "".join(f"\t\t\t\t{g},\n" for g in groups)
        return (f"\t\t{u} /* {name} */ = {{\n\t\t\tisa = PBXNativeTarget;\n"
                f"\t\t\tbuildConfigurationList = {cfgs};\n"
                f"\t\t\tbuildPhases = (\n{ph}\t\t\t);\n\t\t\tbuildRules = (\n\t\t\t);\n\t\t\tdependencies = (\n{deps}\t\t\t);\n"
                f"\t\t\tfileSystemSynchronizedGroups = (\n{gs}\t\t\t);\n"
                f"\t\t\tname = {name};\n\t\t\tpackageProductDependencies = (\n\t\t\t);\n\t\t\tproductName = {name};\n"
                f"\t\t\tproductReference = {ref};\n"
                f"\t\t\tproductType = \"{ptype}\";\n\t\t}};")

    objects = []
    objects.append(("PBXContainerItemProxy", [
        f"\t\t{proxy} = {{\n\t\t\tisa = PBXContainerItemProxy;\n\t\t\tcontainerPortal = {project};\n"
        f"\t\t\tproxyType = 1;\n\t\t\tremoteGlobalIDString = {target};\n\t\t\tremoteInfo = {APP};\n\t\t}};"]))
    objects.append(("PBXFileReference", [
        f"\t\t{app_ref} /* {APP}.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; "
        f"includeInIndex = 0; path = {APP}.app; sourceTree = BUILT_PRODUCTS_DIR; }};",
        f"\t\t{ui_ref} /* {UI}.xctest */ = {{isa = PBXFileReference; explicitFileType = wrapper.cfbundle; "
        f"includeInIndex = 0; path = {UI}.xctest; sourceTree = BUILT_PRODUCTS_DIR; }};"]))
    objects.append(("PBXFileSystemSynchronizedRootGroup", [
        f"\t\t{app_group} /* {APP} */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = {APP}; sourceTree = \"<group>\"; }};",
        f"\t\t{core_group} /* Core */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = Core; sourceTree = \"<group>\"; }};",
        f"\t\t{ui_group} /* {UI} */ = {{isa = PBXFileSystemSynchronizedRootGroup; path = {UI}; sourceTree = \"<group>\"; }};"]))
    objects.append(("PBXFrameworksBuildPhase", [phase(p_frameworks, "PBXFrameworksBuildPhase", "Frameworks"),
                                                phase(u_frameworks, "PBXFrameworksBuildPhase", "Frameworks")]))
    objects.append(("PBXGroup", [
        f"\t\t{main_group} = {{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\t{app_group} /* {APP} */,\n"
        f"\t\t\t\t{core_group} /* Core */,\n\t\t\t\t{ui_group} /* {UI} */,\n"
        f"\t\t\t\t{products} /* Products */,\n\t\t\t);\n\t\t\tsourceTree = \"<group>\";\n\t\t}};",
        f"\t\t{products} /* Products */ = {{\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\t{app_ref} /* {APP}.app */,\n"
        f"\t\t\t\t{ui_ref} /* {UI}.xctest */,\n\t\t\t);\n\t\t\tname = Products;\n\t\t\tsourceTree = \"<group>\";\n\t\t}};"]))
    objects.append(("PBXNativeTarget", [
        native(target, APP, target_list, [p_sources, p_frameworks, p_resources], [app_group, core_group], app_ref,
               "com.apple.product-type.application"),
        native(ui_target, UI, ui_list, [u_sources, u_frameworks, u_resources], [ui_group], ui_ref,
               "com.apple.product-type.bundle.ui-testing", deps=f"\t\t\t\t{dep},\n"),
    ]))
    regions = "".join(f"\t\t\t\t{r if r.isalnum() else q(r)},\n" for r in REGIONS)
    objects.append(("PBXProject", [
        f"\t\t{project} /* Project object */ = {{\n\t\t\tisa = PBXProject;\n\t\t\tattributes = {{\n"
        f"\t\t\t\tBuildIndependentTargetsInParallel = 1;\n\t\t\t\tLastSwiftUpdateCheck = 2700;\n"
        f"\t\t\t\tLastUpgradeCheck = 2700;\n\t\t\t\tTargetAttributes = {{\n\t\t\t\t\t{target} = {{\n"
        f"\t\t\t\t\t\tCreatedOnToolsVersion = 27.0;\n\t\t\t\t\t}};\n\t\t\t\t\t{ui_target} = {{\n"
        f"\t\t\t\t\t\tCreatedOnToolsVersion = 27.0;\n\t\t\t\t\t\tTestTargetID = {target};\n\t\t\t\t\t}};\n\t\t\t\t}};\n\t\t\t}};\n"
        f"\t\t\tbuildConfigurationList = {proj_list};\n"
        f"\t\t\tdevelopmentRegion = en;\n\t\t\thasScannedForEncodings = 0;\n\t\t\tknownRegions = (\n{regions}\t\t\t);\n"
        f"\t\t\tmainGroup = {main_group};\n\t\t\tminimizedProjectReferenceProxies = 1;\n"
        f"\t\t\tpreferredProjectObjectVersion = 77;\n\t\t\tproductRefGroup = {products} /* Products */;\n"
        f"\t\t\tprojectDirPath = \"\";\n\t\t\tprojectRoot = \"\";\n\t\t\ttargets = (\n\t\t\t\t{target} /* {APP} */,\n"
        f"\t\t\t\t{ui_target} /* {UI} */,\n\t\t\t);\n\t\t}};"]))
    objects.append(("PBXResourcesBuildPhase", [phase(p_resources, "PBXResourcesBuildPhase", "Resources"),
                                               phase(u_resources, "PBXResourcesBuildPhase", "Resources")]))
    objects.append(("PBXSourcesBuildPhase", [phase(p_sources, "PBXSourcesBuildPhase", "Sources"),
                                             phase(u_sources, "PBXSourcesBuildPhase", "Sources")]))
    objects.append(("PBXTargetDependency", [
        f"\t\t{dep} = {{\n\t\t\tisa = PBXTargetDependency;\n\t\t\ttarget = {target};\n\t\t\ttargetProxy = {proxy};\n\t\t}};"]))

    def cfg(u, name, settings):
        return (f"\t\t{u} /* {name} */ = {{\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {{\n"
                f"{settings_block(settings, 4)}\n\t\t\t}};\n\t\t\tname = {name};\n\t\t}};")
    objects.append(("XCBuildConfiguration", [
        cfg(proj_debug, "Debug", proj_debug_s), cfg(proj_release, "Release", proj_release_s),
        cfg(tgt_debug, "Debug", target_common), cfg(tgt_release, "Release", target_common),
        cfg(ui_debug, "Debug", ui_common), cfg(ui_release, "Release", ui_common)]))

    def cfglist(u, d, r):
        return (f"\t\t{u} = {{\n\t\t\tisa = XCConfigurationList;\n"
                f"\t\t\tbuildConfigurations = (\n\t\t\t\t{d} /* Debug */,\n\t\t\t\t{r} /* Release */,\n\t\t\t);\n"
                f"\t\t\tdefaultConfigurationIsVisible = 0;\n\t\t\tdefaultConfigurationName = Release;\n\t\t}};")
    objects.append(("XCConfigurationList", [
        cfglist(proj_list, proj_debug, proj_release), cfglist(target_list, tgt_debug, tgt_release),
        cfglist(ui_list, ui_debug, ui_release)]))

    out = ["// !$*UTF8*$!", "{", "\tarchiveVersion = 1;", "\tclasses = {", "\t};", "\tobjectVersion = 77;", "\tobjects = {", ""]
    for section, entries in objects:
        out.append(f"/* Begin {section} section */")
        out.extend(entries)
        out.append(f"/* End {section} section */")
        out.append("")
    out.append("\t};")
    out.append(f"\trootObject = {project} /* Project object */;")
    out.append("}")

    os.makedirs(os.path.join(PROJ, "xcshareddata", "xcschemes"), exist_ok=True)
    with open(os.path.join(PROJ, "project.pbxproj"), "w") as f:
        f.write("\n".join(out) + "\n")
    with open(os.path.join(PROJ, "xcshareddata", "xcschemes", f"{APP}.xcscheme"), "w") as f:
        f.write(SCHEME.replace("UI_TARGET_ID", ui_target).replace("TARGET_ID", target))
    print("wrote", PROJ)


SCHEME = """<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2700" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "TARGET_ID" BuildableName = "QiYan.app" BlueprintName = "QiYan" ReferencedContainer = "container:QiYan.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
         <TestableReference skipped = "NO">
            <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "UI_TARGET_ID" BuildableName = "QiYanUITests.xctest" BlueprintName = "QiYanUITests" ReferencedContainer = "container:QiYan.xcodeproj">
            </BuildableReference>
         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference BuildableIdentifier = "primary" BlueprintIdentifier = "TARGET_ID" BuildableName = "QiYan.app" BlueprintName = "QiYan" ReferencedContainer = "container:QiYan.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug">
   </AnalyzeAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
"""

if __name__ == "__main__":
    main()
