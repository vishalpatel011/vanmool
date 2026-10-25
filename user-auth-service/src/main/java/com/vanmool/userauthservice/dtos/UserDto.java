package com.vanmool.userauthservice.dtos;

import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

// What the API shows of a user: never the password hash
@Getter
@Setter
public class UserDto {
    private UUID id;
    private String name;
    private String email;
    private List<Role> roles;

    public static UserDto from(User user){
        UserDto userDto = new UserDto();
        userDto.id = user.getId();
        userDto.name = user.getName();
        userDto.email = user.getEmail();
        // Sorted, so the JSON is the same every time (a Set has no order)
        userDto.roles = user.getRoles().stream().sorted().toList();
        return userDto;
    }
}
